import os
import sys
import time
import re
import hashlib
import uuid

# Configurar salida UTF-8 para consola Windows
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

import psycopg2
from qdrant_client import QdrantClient
from qdrant_client.http import models as qmodels
from google import genai
from google.genai import types

# -------------------------------------------------------------
# Cargar archivo .env local si existe
# -------------------------------------------------------------
env_path = os.path.join(os.path.dirname(__file__), "..", ".env")
if os.path.exists(env_path):
    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip())

# -------------------------------------------------------------
# 1. Configuración de API Keys con Pool de Rotación
# -------------------------------------------------------------
raw_keys = os.getenv("GEMINI_API_KEY", "")
API_KEYS = [k.strip() for k in raw_keys.split(",") if k.strip()]

QDRANT_URL = os.getenv("QDRANT_URL")
QDRANT_API_KEY = os.getenv("QDRANT_API_KEY")
QDRANT_COLLECTION = os.getenv("QDRANT_COLLECTION", "springrag")
NEON_DB_URL = os.getenv("NEON_DB_URL")

if not API_KEYS or not QDRANT_URL or not NEON_DB_URL:
    print("[ERROR] Faltan variables de entorno requeridas:")
    sys.exit(1)

if NEON_DB_URL.startswith("jdbc:postgresql://"):
    NEON_DB_URL = NEON_DB_URL.replace("jdbc:postgresql://", "postgresql://")

print(f"[GEMINI] Pool de rotacion activo con {len(API_KEYS)} API Keys disponibles.")
gemini_clients = [genai.Client(api_key=key) for key in API_KEYS]
current_key_idx = 0

# -------------------------------------------------------------
# 2. Inicializar Clientes
# -------------------------------------------------------------
print("[QDRANT] Conectando con Qdrant Cloud...")
qdrant = QdrantClient(url=QDRANT_URL, api_key=QDRANT_API_KEY)

try:
    col_info = qdrant.get_collection(QDRANT_COLLECTION)
    vparams = col_info.config.params.vectors
    if hasattr(vparams, "size"):
        expected_dim = vparams.size
    elif isinstance(vparams, dict):
        if "size" in vparams:
            expected_dim = vparams["size"]
        elif "default" in vparams:
            expected_dim = getattr(vparams["default"], "size", 3072)
        else:
            expected_dim = 3072
    else:
        expected_dim = 3072
    print(f"[QDRANT] Coleccion '{QDRANT_COLLECTION}' lista. Dimension: {expected_dim}")
except Exception as e:
    expected_dim = 3072

# -------------------------------------------------------------
# 3. Gestión de Conexión a Neon
# -------------------------------------------------------------
def ejecutar_sql(query, params=None, fetch_one=False):
    with psycopg2.connect(NEON_DB_URL) as conn:
        with conn.cursor() as cur:
            cur.execute(query, params)
            if fetch_one:
                row = cur.fetchone()
                return row[0] if row else None
            conn.commit()

ejecutar_sql("""
    CREATE TABLE IF NOT EXISTS registro_leyes (
        nombre_archivo VARCHAR(255) PRIMARY KEY,
        hash_archivo VARCHAR(64) NOT NULL,
        total_fragmentos INT NOT NULL,
        fecha_procesado TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
""")
print("[NEON] Tabla 'registro_leyes' verificada en Neon.")

def obtener_hash_guardado(nombre_archivo):
    return ejecutar_sql("SELECT hash_archivo FROM registro_leyes WHERE nombre_archivo = %s;", (nombre_archivo,), fetch_one=True)

def actualizar_registro(nombre_archivo, hash_archivo, total_fragmentos):
    ejecutar_sql("""
        INSERT INTO registro_leyes (nombre_archivo, hash_archivo, total_fragmentos, fecha_procesado)
        VALUES (%s, %s, %s, CURRENT_TIMESTAMP)
        ON CONFLICT (nombre_archivo) 
        DO UPDATE SET hash_archivo = EXCLUDED.hash_archivo,
                      total_fragmentos = EXCLUDED.total_fragmentos,
                      fecha_procesado = CURRENT_TIMESTAMP;
    """, (nombre_archivo, hash_archivo, total_fragmentos))

# -------------------------------------------------------------
# 4. Funciones de Chunking
# -------------------------------------------------------------
def calcular_sha256(texto):
    return hashlib.sha256(texto.encode("utf-8")).hexdigest()

def dividir_por_articulos(texto, max_len=1200, overlap=150):
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
            parrafos = [p.strip() for p in seccion.split("\n\n") if p.strip()]
            buffer = ""
            for p in parrafos:
                if len(buffer) + len(p) + 2 <= max_len:
                    buffer = f"{buffer}\n\n{p}".strip()
                else:
                    if buffer:
                        chunks.append(buffer)
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
# 5. Generación de Embeddings con Auto-Recuperación Robusta
# -------------------------------------------------------------
def generar_embeddings_lote(textos, dim_esperada=3072, max_retries=30):
    global current_key_idx
    config_dict = {}
    if dim_esperada in (768, 1536, 3072):
        config_dict["output_dimensionality"] = dim_esperada

    for attempt in range(max_retries):
        client = gemini_clients[current_key_idx]
        try:
            response = client.models.embed_content(
                model="gemini-embedding-001",
                contents=textos,
                config=types.EmbedContentConfig(**config_dict) if config_dict else None
            )
            return [e.values for e in response.embeddings]
        except Exception as e:
            err_msg = str(e)
            if "429" in err_msg or "RESOURCE_EXHAUSTED" in err_msg or "quota" in err_msg.lower():
                antigua_key = current_key_idx + 1
                current_key_idx = (current_key_idx + 1) % len(gemini_clients)
                nueva_key = current_key_idx + 1
                
                # Si todas las 3 keys están en su límite por minuto, descansar 30s
                if current_key_idx == 0:
                    print(f"\n  ⏳ [PAUSA DE SEGURIDAD] Todas las keys ocupadas. Descansando 30s...", end=" ", flush=True)
                    time.sleep(30)
                    print("Reanudando.")
                else:
                    print(f"\n  [ROTACIÓN] Key #{antigua_key} en pausa. Usando Key #{nueva_key}...", end=" ", flush=True)
                    time.sleep(1.5)
            else:
                print(f"\n  ⚠️ [WARN] Error en intento {attempt+1}/{max_retries}: {e}")
                time.sleep(4)

    raise RuntimeError(f"Fallo al generar embeddings tras {max_retries} intentos.")

# -------------------------------------------------------------
# 6. Proceso Principal de Sincronización
# -------------------------------------------------------------
def sincronizar():
    carpeta_normativa = os.path.join(os.path.dirname(__file__), "..", "normativa")
    if not os.path.exists(carpeta_normativa):
        carpeta_normativa = "normativa"

    if not os.path.exists(carpeta_normativa):
        print(f"[ERROR] No se encontro la carpeta '{carpeta_normativa}'.")
        sys.exit(1)

    archivos = sorted([
        f for f in os.listdir(carpeta_normativa)
        if (f.endswith(".md") or f.endswith(".txt")) and not f.lower().startswith("readme")
    ])

    if not archivos:
        print("[WARN] No hay archivos de leyes en la carpeta normativa.")
        return

    print(f"\n[INFO] Leyes oficiales encontradas ({len(archivos)}):")
    for a in archivos:
        print(f"  - {a}")
    print("=" * 60)

    for idx, nombre_archivo in enumerate(archivos, 1):
        ruta_archivo = os.path.join(carpeta_normativa, nombre_archivo)
        with open(ruta_archivo, "r", encoding="utf-8", errors="ignore") as f:
            contenido = f.read()

        hash_actual = calcular_sha256(contenido)
        hash_previo = obtener_hash_guardado(nombre_archivo)

        print(f"\n[{idx}/{len(archivos)}] Procesando: {nombre_archivo}")

        if hash_previo == hash_actual:
            print(f"  [OK] SIN CAMBIOS (Hash SHA-256 coincide en Neon). Saltando para ahorrar tokens.")
            continue

        if hash_previo is not None:
            print(f"  [UPDATE] MODIFICADO: Actualizando puntos en Qdrant...")
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
            except Exception as e:
                print(f"  [WARN] Limpieza previa: {e}")
        else:
            print(f"  [NEW] NUEVA LEY: Indexando por primera vez.")

        chunks = dividir_por_articulos(contenido)
        print(f"  [CHUNKS] Total fragmentos a vectorizar: {len(chunks)}")

        BATCH_SIZE = 10
        total_batches = (len(chunks) + BATCH_SIZE - 1) // BATCH_SIZE
        total_guardados = 0

        for b_idx in range(total_batches):
            inicio = b_idx * BATCH_SIZE
            fin = min(inicio + BATCH_SIZE, len(chunks))
            lote_textos = chunks[inicio:fin]

            print(f"  [VECTOR] Lote {b_idx + 1}/{total_batches} ({len(lote_textos)} articulos)...", end=" ", flush=True)
            vectores = generar_embeddings_lote(lote_textos, dim_esperada=expected_dim)

            lote_puntos = []
            for i, (texto_chunk, vector) in enumerate(zip(lote_textos, vectores)):
                chunk_index = inicio + i
                point_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, f"{nombre_archivo}_{chunk_index}"))

                punto = qmodels.PointStruct(
                    id=point_id,
                    vector=vector,
                    payload={
                        "doc_content": texto_chunk,
                        "file_name": nombre_archivo,
                        "original_filename": nombre_archivo,
                        "tipo": "ley_oficial",
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
                lote_puntos.append(punto)

            qdrant.upsert(collection_name=QDRANT_COLLECTION, points=lote_puntos)
            total_guardados += len(lote_puntos)
            print("OK [Guardado en Qdrant]")

            # Pausa de 2.5s entre lotes
            if b_idx < total_batches - 1:
                time.sleep(2.5)

        actualizar_registro(nombre_archivo, hash_actual, total_guardados)
        print(f"  [SUCCESS] '{nombre_archivo}' sincronizado exitosamente ({total_guardados} puntos guardados en Qdrant y Neon).")

    print("\n" + "=" * 60)
    print("[COMPLETED] ¡Las 6 leyes oficiales quedaron sincronizadas con exito!")

if __name__ == "__main__":
    sincronizar()
