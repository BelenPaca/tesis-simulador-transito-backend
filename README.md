# TransiAI Backend: Servicio RAG de Normativa de Tránsito

## Descripción
Servicio backend de alto rendimiento desarrollado en Java y Spring Boot para la plataforma TransiAI. Implementa una arquitectura RAG (Retrieval-Augmented Generation) para la recuperación semántica de normativa vial ecuatoriana (COIP, LOTTTSV y Reglamentos) y la generación de respuestas fundamentadas en derecho mediante streaming con Server-Sent Events (SSE).

---

## Arquitectura del Servicio

- **Framework:** Spring Boot 4 / Spring AI
- **Runtime:** Java 25 / OpenJDK Temurin optimizado para entornos de memoria acotada.
- **Transmisión de Respuestas:** Protocolo Server-Sent Events (SSE) token por token.
- **Indexación y Búsqueda Semántica:** Integración con clúster Qdrant Cloud a través de gRPC con cifrado TLS.
- **Persistencia Transaccional:** Conexión a Neon PostgreSQL mediante pool de conexiones HikariCP con SSL.
- **Procesamiento de Documentos:** Ingesta y extracción de reportes técnicos periciales y normativas mediante Apache Tika.

---

## Entornos de Operación

### 1. Despliegue en la Nube (Producción en Render)
- Alojado en Render (PaaS) mediante compilación multi-etapa en contenedor Docker.
- Optimización de memoria JVM (`-Xms128m -Xmx220m -XX:+UseSerialGC`) para máxima estabilidad operativa.
- Endpoint de verificación de estado y monitoreo: `/actuator/health`.

### 2. Ejecución Local
- Requiere instancias locales de PostgreSQL y Qdrant (disponibles mediante el archivo `podman-compose.yml`).
- Configurable mediante variables de entorno o archivo `application.yml`.

---

## Variables de Entorno Principales

- `PORT`: Puerto de escucha del servidor (por defecto 8080 en local, asignado por Render en producción).
- `SPRING_AI_GEMINI_API_KEY`: Clave de acceso a los modelos de lenguaje y embeddings.
- `SPRING_DATASOURCE_URL`: URL de conexión JDBC a PostgreSQL (Neon en nube, localhost en local).
- `QDRANT_HOST` y `QDRANT_API_KEY`: Parámetros de conexión al clúster de base de datos vectorial.

---

## Compilación y Ejecución

```bash
# Compilar el proyecto
./gradlew build -x test

# Ejecutar el servicio en modo desarrollo
./gradlew bootRun
```

---

## Licencia y Uso Académico
Proyecto desarrollado como trabajo de titulación e investigación académica. Todos los derechos reservados.
