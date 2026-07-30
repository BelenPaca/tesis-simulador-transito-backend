# Multi-stage Dockerfile para Spring Boot con Java 25
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Copiar archivos Gradle para aprovechar la caché de capas
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# Copiar código fuente
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:25-jre
WORKDIR /app

# Copiar JAR generado
COPY --from=builder /app/build/libs/*.jar app.jar
RUN mkdir -p /app/inbound

EXPOSE ${PORT:-8080}
ENTRYPOINT ["java", "-jar", "app.jar"]
