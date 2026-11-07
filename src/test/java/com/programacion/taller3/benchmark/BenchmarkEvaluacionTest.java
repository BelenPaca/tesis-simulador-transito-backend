package com.programacion.taller3.benchmark;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Suite de Pruebas de Rendimiento y Evaluación de Tesis TransiAI.
 * Ejecuta consultas contra el endpoint SSE /api/chat y cronometra:
 * - TTFT (Time To First Token) en milisegundos.
 * - Tiempo Total de respuesta en milisegundos.
 * Ubicado en src/test/java para mantener el código de producción 100% puro.
 */
public class BenchmarkEvaluacionTest {

    private static final String TARGET_URL = System.getProperty("test.url", "https://tesis-simulador-transito-backend.onrender.com/api/chat");
    // Para probar en entorno local con Podman: System.setProperty("test.url", "http://localhost:8080/api/chat");

    record CasoPrueba(int id, String consulta, String esperado, String gravedad) {}

    private static final List<CasoPrueba> CASOS_CUADRO_4_2 = List.of(
            new CasoPrueba(1, "¿Qué pasa si choco mi carro contra otro vehículo y solo hay daños materiales sin heridos?", "Art. 380 COIP", "LEVE"),
            new CasoPrueba(2, "¿Cuánto tengo que pagar de multa si me detienen y se me caducó la licencia de conducir?", "Art. 387 num. 2 COIP", "LEVE"),
            new CasoPrueba(3, "¿Qué sanción tiene conducir un vehículo con las llantas lisas o en mal estado?", "Art. 383 COIP", "GRAVE"),
            new CasoPrueba(4, "Un radar me tomó a 115 km/h en la carretera recta, ¿es multa o cárcel?", "Art. 389 num. 6 COIP / Art. 191 Reg.", "GRAVE"),
            new CasoPrueba(5, "¿Puedo ponerle láminas oscuras o polarizados a los vidrios de mi auto?", "Art. 239 Reg. / Art. 391 num. 11 COIP", "LEVE"),
            new CasoPrueba(6, "¿Qué sanción tengo si voy chateando en el celular mientras manejo?", "Art. 391 num. 12 COIP", "LEVE"),
            new CasoPrueba(7, "Me estacioné un momento en la parada del bus para dejar a un familiar, ¿es infracción?", "Art. 179 num. 16 Reg. / Art. 391 num. 5", "LEVE"),
            new CasoPrueba(8, "¿Cuál es la sanción y los días de prisión por conducir en estado de embriaguez?", "Art. 385 COIP", "MUY GRAVE")
    );

    @Test
    @DisplayName("Ejecución de Benchmark de Latencias (TTFT y Tiempo Total) en streaming SSE")
    void ejecutarBenchmarkLatencias() {
        System.out.println("===============================================================");
        System.out.println(" INICIANDO BENCHMARK EN JAVA - TRANSIAI");
        System.out.println(" Endpoint objetivo: " + TARGET_URL);
        System.out.println("===============================================================\n");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        for (CasoPrueba caso : CASOS_CUADRO_4_2) {
            evaluarConsulta(client, caso);
        }

        System.out.println("\nEvaluación completada con éxito.");
    }

    private void evaluarConsulta(HttpClient client, CasoPrueba caso) {
        String jsonPayload = String.format("""
                {
                    "message": "%s",
                    "conversationId": "%s",
                    "userId": "evaluador_tesis_java"
                }
                """, caso.consulta().replace("\"", "\\\""), UUID.randomUUID());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TARGET_URL))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        long tInicio = System.nanoTime();
        AtomicLong tPrimerToken = new AtomicLong(0);
        AtomicBoolean primerTokenRecibido = new AtomicBoolean(false);
        StringBuilder fullResponse = new StringBuilder();

        try {
            client.send(request, HttpResponse.BodyHandlers.ofLines())
                    .body()
                    .forEach(line -> {
                        if (line.startsWith("event: token") || line.startsWith("data:")) {
                            if (primerTokenRecibido.compareAndSet(false, true)) {
                                tPrimerToken.set(System.nanoTime());
                            }
                            fullResponse.append(line).append("\n");
                        }
                    });

            long tFin = System.nanoTime();
            double ttftMs = (tPrimerToken.get() > 0 ? (tPrimerToken.get() - tInicio) : (tFin - tInicio)) / 1_000_000.0;
            double totalMs = (tFin - tInicio) / 1_000_000.0;

            System.out.printf("[%d/8] Consulta: %s%n", caso.id(), caso.consulta().substring(0, Math.min(50, caso.consulta().length())) + "...");
            System.out.printf("      TTFT: %.2f ms | Tiempo Total: %.2f ms%n", ttftMs, totalMs);

        } catch (Exception e) {
            long tFin = System.nanoTime();
            double totalMs = (tFin - tInicio) / 1_000_000.0;
            System.out.printf("[%d/8] Consulta: %s -> Error / Timeout (%.2f ms): %s%n",
                    caso.id(), caso.consulta(), totalMs, e.getMessage());
        }
    }
}

