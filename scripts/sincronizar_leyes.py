import os
import sys
import time
import re
import hashlib
import uuid
import psycopg2
from qdrant_client import QdrantClient
from qdrant_client.http import models as qmodels

# -------------------------------------------------------------
# 1. Configuración y Variables de Entorno
# -------------------------------------------------------------
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY")
QDRANT_URL = os.getenv("QDRANT_URL")
QDRANT_API_KEY = os.getenv("QDRANT_API_KEY")
QDRANT_COLLECTION = os.getenv("QDRANT_COLLECTION", "springrag")
NEON_DB_URL = os.getenv("NEON_DB_URL")

if not all([GEMINI_API_KEY, QDRANT_URL, NEON_DB_URL]):
    print("❌ ERROR: Faltan variables de entorno requeridas:")
    print(f"  - GEMINI_API_KEY: {'OK' if GEMINI_API_KEY else 'FALTA'}")
    print(f"  - QDRANT_URL:     {'OK' if QDRANT_URL else 'FALTA'}")
    print(f"  - NEON_DB_URL:    {'OK' if NEON_DB_URL else 'FALTA'}")
    sys.exit(1)

# Normalizar URL de Neon si viene con formato JDBC
if NEON_DB_URL.startswith("jdbc:postgresql://"):
    NEON_DB_URL = NEON_DB_URL.replace("jdbc:postgresql://", "postgresql://")

# -------------------------------------------------------------
# 2. Inicializar Clientes
# -------------------------------------------------------------
print("🔌 Conectando con Qdrant Cloud...")
qdrant = QdrantClient(url=QDRANT_URL, api_key=QDRANT_API_KEY)

# Detectar dimensión esperada por la colección de Qdrant
try:
    col_info = qdrant.get_collection(QDRANT_COLLECTION)
    expected_dim = col_info.config.params.vectors.size
    print(f"✅ Colección '{QDRANT_COLLECTION}' encontrada. Dimensión esperada: {expected_dim}")
except Exception as e:
    print(f"⚠️ No se pudo leer la dimensión de '{QDRANT_COLLECTION}', usando 3072 por defecto: {e}")
    expected_dim = 3072

print("🔌 Conectando con Google Gemini...")
from google import genai
from google.genai import types

gemini_client = genai.Client(api_key=GEMINI_API_KEY)

# -------------------------------------------------------------
# 3. Inicializar Tabla en Neon
# -------------------------------------------------------------
print("🔌 Conectando con Neon PostgreSQL...")
conn = psycopg2.connect(NEON_DB_URL)
cursor = conn.cursor()

cursor.execute("""
    CREATE TABLE IF NOT EXISTS registro_leyes (
        nombre_archivo VARCHAR(255) PRIMARY KEY,
        hash_archivo VARCHAR(64) NOT NULL,
        total_fragmentos INT NOT NULL,
        fecha_procesado TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
""")
conn.commit()
print("✅ Tabla 'registro_leyes' lista en Neon.")

def obtener_hash_guardado(nombre_archivo):
    cursor.execute("SELECT hash_archivo FROM registro_leyes WHERE nombre_archivo = %s;", (nombre_archivo,))
    row = cursor.fetchone()
    return row[0] if row else None

def actualizar_registro(nombre_archivo, hash_archivo, total_fragmentos):
    cursor.execute("""
        INSERT INTO registro_leyes (nombre_archivo, hash_archivo, total_fragmentos, fecha_procesado)
        VALUES (%s, %s, %s, CURRENT_TIMESTAMP)
        ON CONFLICT (nombre_archivo) 
        DO UPDATE SET hash_archivo = EXCLUDED.hash_archivo,
                      total_fragmentos = EXCLUDED.total_fragmentos,
                      fecha_procesado = CURRENT_TIMESTAMP;
    """, (nombre_archivo, hash_archivo, total_fragmentos))
    conn.commit()

# -------------------------------------------------------------
# 4. Funciones de Chunking (Segmentación de Leyes)
# -------------------------------------------------------------
def calcular_sha256(texto):
    return hashlib.sha256(texto.encode("utf-8")).hexdigest()

def dividir_por_articulos(texto, max_len=1200, overlap=150):
    """
    Divide el Markdown legal por artículos o encabezados manteniendo el contexto.
    """
    # Patrón para detectar artículos o títulos legales
    patron = re.compile(r'(?=^#{1,4}\s*(?:Art(?:ículo|\.)|Capítulo|Título|SECCIÓN)\b)', re.IGNORECASE | re.MULTILINE)
    secciones = [s.strip() for s in patron.split(texto) if s.strip()]

    if not secciones:
        secciones = [texto]

    chunks = []
    for seccion in secciones:
        if len(seccion) <= max_len:
            if len(seccion) > 30:
                chunks.append(seccion)
        else:
            # Si un artículo es muy largo, partirlo por párrafos
            parrafos = [p.strip() for p in seccion.split("\n\n") if p.strip()]
            buffer = ""
            for p in parrafos:
                if len(buffer) + len(p) + 2 <= max_len:
                    buffer = f"{buffer}\n\n{p}".strip()
                else:
                    if buffer:
                        chunks.append(buffer)
                    # Si un solo párrafo excede max_len, partirlo por longitud con overlap
                    if len(p) > max_len:
                        for i in range(0, len(p), max_len - overlap):
                            chunks.append(p[i:i + max_len])
                        buffer = ""
                    else:
                        buffer = p
            if buffer:
                chunks.append(buffer)

    return chunks

# -------------------------------------------------------------
# 5. Generación de Embeddings con Gemini
# -------------------------------------------------------------
def generar_embeddings_lote(textos, dim_esperada=3072, retries=3):
    """
    Llama a Gemini para obtener los embeddings del lote con control de rate limit.
    """
    config_dict = {}
    if dim_esperada in (768, 1536, 3072):
        config_dict["output_dimensionality"] = dim_esperada

    for attempt in range(retries):
        try:
            # Modelo estándar para embeddings en Gemini
            response = gemini_client.models.embed_content(
                model="gemini-embedding-001",
                contents=textos,
                config=types.EmbedContentConfig(**config_dict) if config_dict else None
            )
            # Extraer vectores de la respuesta
            return [e.values for e in response.embeddings]
        except Exception as e:
            err_msg = str(e)
            if "429" in err_msg or "RESOURCE_EXHAUSTED" in err_msg:
                wait_time = (attempt + 1) * 8
                print(f"  ⏳ Límite de tasa alcanzado. Esperando {wait_time}s antes de reintentar...")
                time.sleep(wait_time)
            else:
                print(f"  ⚠️ Error en embeddings (intento {attempt+1}/{retries}): {e}")
                time.sleep(3)

    raise RuntimeError(f"Fallo al generar embeddings tras {retries} intentos.")

# -------------------------------------------------------------
# 6. Proceso Principal de Sincronización
# -------------------------------------------------------------
def sincronizar():
    carpeta_normativa = os.path.join(os.path.dirname(__file__), "..", "normativa")
    if not os.path.exists(carpeta_normativa):
        carpeta_normativa = "normativa"

    if not os.path.exists(carpeta_normativa):
        print(f"❌ ERROR: No se encontró la carpeta '{carpeta_normativa}'.")
        sys.exit(1)

    archivos = sorted([f for f in os.listdir(carpeta_normativa) if f.endswith(".md") or f.endswith(".txt")])
    if not archivos:
        print("⚠️ No hay archivos .md en la carpeta normativa.")
        return

    print(f"\n📂 Archivos encontrados para evaluar ({len(archivos)}):")
    for a in archivos:
        print(f"  - {a}")
    print("=" * 60)

    for idx, nombre_archivo in enumerate(archivos, 1):
        ruta_archivo = os.path.join(carpeta_normativa, nombre_archivo)
        with open(ruta_archivo, "r", encoding="utf-8", errors="ignore") as f:
            contenido = f.read()

        hash_actual = calcular_sha256(contenido)
        hash_previo = obtener_hash_guardado(nombre_archivo)

        print(f"\n[{idx}/{len(archivos)}] Analizando: {nombre_archivo}")

        if hash_previo == hash_actual:
            print(f"  🟢 SIN CAMBIOS (Hash SHA-256 coincide). Saltando para ahorrar tokens.")
            continue

        if hash_previo is not None:
            print(f"  🔄 MODIFICADO: Se detectaron cambios en el archivo. Actualizando en Qdrant...")
            # Borrar puntos anteriores de este archivo para evitar duplicados
            try:
                qdrant.delete(
                    collection_name=QDRANT_COLLECTION,
                    points_selector=qmodels.FilterSelector(
                        filter=qmodels.Filter(
                            must=[
                                qmodels.FieldCondition(
                                    key="file_name",
                                    match=qmodels.MatchValue(value=nombre_archivo)
                                )
                            ]
                        )
                    )
                )
                print(f"  🗑️ Puntos anteriores eliminados de Qdrant.")
            except Exception as e:
                print(f"  ⚠️ Nota al limpiar puntos previos: {e}")
        else:
            print(f"  ✨ NUEVO: Es la primera vez que se procesa este archivo.")

        # Dividir en fragmentos
        chunks = dividir_por_articulos(contenido)
        print(f"  📑 Total fragmentos generados: {len(chunks)}")

        # Procesar en lotes de 15 para cuidar la API de Gemini
        BATCH_SIZE = 15
        puntos_qdrant = []
        total_batches = (len(chunks) + BATCH_SIZE - 1) // BATCH_SIZE

        for b_idx in range(total_batches):
            inicio = b_idx * BATCH_SIZE
            fin = min(inicio + BATCH_SIZE, len(chunks))
            lote_textos = chunks[inicio:fin]

            print(f"  🚀 Vectorizando lote {b_idx + 1}/{total_batches} ({len(lote_textos)} artículos)...")
            vectores = generar_embeddings_lote(lote_textos, dim_esperada=expected_dim)

            for i, (texto_chunk, vector) in enumerate(zip(lote_textos, vectores)):
                chunk_index = inicio + i
                # UUID determinista para evitar cualquier duplicación
                point_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{nombre_archivo}_{chunk_index}"))

                punto = qmodels.PointStruct(
                    id=point_id,
                    vector=vector,
                    payload={
                        "doc_content": texto_chunk,        # Clave estándar para Spring AI
                        "file_name": nombre_archivo,       # Clave para pastillas en Vue
                        "original_filename": nombre_archivo,
                        "tipo": "ley_oficial",             # Etiqueta para scope global
                        "source": nombre_archivo,
                        "metadata": {
                            "file_name": nombre_archivo,
                            "original_filename": nombre_archivo,
                            "tipo": "ley_oficial",
                            "source": nombre_archivo,
                            "chunk_index": chunk_index
                        }
                    }
                )
                puntos_qdrant.append(punto)

            # Pausa de 2.5 segundos para respetar los límites por minuto de Google
            if b_idx < total_batches - 1:
                time.sleep(2.5)

        # Subir todos los puntos a Qdrant Cloud
        print(f"  💾 Guardando {len(puntos_qdrant)} vectores en Qdrant Cloud...")
        qdrant.upsert(collection_name=QDRANT_COLLECTION, points=puntos_qdrant)

        # Actualizar huella en Neon
        actualizar_registro(nombre_archivo, hash_actual, len(puntos_qdrant))
        print(f"  ✅ '{nombre_archivo}' sincronizado exitosamente ({len(puntos_qdrant)} puntos guardados).")

    print("\n" + "=" * 60)
    print("🎉 ¡Sincronización completa finalizada con éxito!")

if __name__ == "__main__":
    try:
        sincronizar()
    finally:
        cursor.close()
        conn.close()

