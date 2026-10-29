package com.programacion.taller3.rest;

import com.programacion.taller3.services.ConversationService;
import org.apache.camel.spi.RestConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;
import java.util.UUID;

@RestController
public class ChatController {

    private final ChatClient chatClient;
    private final RestConfiguration configuration;

    @Value("classpath:/prompts/systemPrompt.st")
    Resource systemPrompt;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    ConversationService conversationService;


    public ChatController(ChatClient.Builder builder, RestConfiguration configuration){
        this.chatClient = builder
                .defaultAdvisors(
                        // Esto es para imprimir el log de peticiones
                        new SimpleLoggerAdvisor()
                )
                .build();
        this.configuration = configuration;

    }

    private String searchDocuments(String query){
        var request = SearchRequest.builder()
                .query(query)
                .topK(3)
                .build();

        var documents = vectorStore.similaritySearch(request);

        return documents.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + System.lineSeparator() + b)
                .trim();
    }


    @PostMapping(path = "/api/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {

        var message = request.message();
        var conversationId = request.conversationId() != null ? request.conversationId() : UUID.randomUUID().toString();
        var userId = request.userId() != null && !request.userId().isBlank()
                ? request.userId()
                : (headerUserId != null && !headerUserId.isBlank() ? headerUserId : "invitado");

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("El mensaje no puede estar vacío");
        }

        // Guardar mensaje del usuario en PostgreSQL para la UI
        conversationService.saveMessage(conversationId, "user", message, userId);

        // Buscar manualmente en Qdrant los documentos RAG
        String attachedFile = request.attachedFileName();
        java.util.List<Document> documents;

        if (attachedFile != null && !attachedFile.isBlank()) {
            String safeFile = attachedFile.replace("'", "");
            var searchWithFile = SearchRequest.builder()
                    .query(message)
                    .topK(6)
                    .filterExpression("categoria == 'documento' && (source == '" + safeFile + "' || file_name == '" + safeFile + "')")
                    .build();
            documents = vectorStore.similaritySearch(searchWithFile);

            if (documents.isEmpty()) {
                documents = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(message)
                        .topK(6)
                        .filterExpression("categoria == 'documento'")
                        .build());
            }
        } else {
            var searchRequest = SearchRequest.builder()
                    .query(message)
                    .topK(6)
                    .filterExpression("categoria == 'documento'")
                    .build();
            documents = vectorStore.similaritySearch(searchRequest);
        }

        String context = documents.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);

        if (attachedFile != null && !attachedFile.isBlank()) {
            context = "=== CONTEXTO DEL DOCUMENTO ADJUNTO POR EL USUARIO (" + attachedFile + ") ===\n" + context;
        }

        String systemMessage;
        try {
            String template = new String(systemPrompt.getContentAsByteArray(), StandardCharsets.UTF_8);
            systemMessage = template.replace("{normativa}", context);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error cargando el archivo systemPrompt.st", e);
        }

        // Envolver el VectorStore para sanear los metadatos complejos que Groq devuelve (evita el error de tipo no soportado en Qdrant)
        var safeVectorStore = new com.programacion.taller3.utils.SafeVectorStore(vectorStore);

        // Advisor para memoria semántica con Qdrant
        var vectorMemoryAdvisor = VectorStoreChatMemoryAdvisor.builder(safeVectorStore).build();

        // Acumular la respuesta completa para guardarla después
        StringBuilder fullResponse = new StringBuilder();

        Flux<ServerSentEvent<String>> tokens = chatClient.prompt()
                .system(systemMessage)
                .user(message)
                .advisors(vectorMemoryAdvisor) // Usamos solo la memoria, el RAG ya lo inyectamos manualmente
                .advisors(a -> a.param("chat_memory_conversation_id", conversationId)) // VectorStoreChatMemoryAdvisor usa 'chat_memory_conversation_id'
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8)))
                        .build()
                );

        // Construir fuentes reales desde Qdrant
        List<Map<String, String>> realSources = buildLegalSources(documents);
        String sourcesJson;
        try {
            sourcesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(realSources);
        } catch (Exception e) {
            sourcesJson = "[]";
        }
        final String finalSourcesJson = sourcesJson;

        // Chunk con las fuentes legales reales de Qdrant al final del stream de tokens
        String sourcesChunk = (!finalSourcesJson.isBlank() && !finalSourcesJson.equals("[]"))
                ? "\n[LEGAL_SOURCES]" + finalSourcesJson + "[/LEGAL_SOURCES]"
                : "";

        Flux<ServerSentEvent<String>> sourcesFlux = sourcesChunk.isBlank()
                ? Flux.empty()
                : Flux.just(ServerSentEvent.<String>builder()
                        .event("token")
                        .data(Base64.getEncoder().encodeToString(sourcesChunk.getBytes(StandardCharsets.UTF_8)))
                        .build()
                );

        // Cuando el stream termine, guardar la respuesta del asistente en PostgreSQL para la UI
        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            if (!fullResponse.isEmpty()) {
                try {
                    String toSave = fullResponse.toString();
                    if (!sourcesChunk.isBlank() && !toSave.contains("[LEGAL_SOURCES]")) {
                        toSave += sourcesChunk;
                    }
                    conversationService.saveMessage(conversationId, "assistant", toSave);
                } catch (Exception e) {
                    System.err.println("Error guardando respuesta del asistente: " + e.getMessage());
                }
            }
            return Flux.just(
                    ServerSentEvent.<String>builder()
                            .event("done")
                            .data("[DONE]")
                            .build()
            );
        });

        return tokens.concatWith(sourcesFlux).concatWith(done)
                .onErrorResume(error -> Flux.just(
                                ServerSentEvent.<String>builder()
                                        .event("error")
                                        .data(error.getMessage())
                                        .build()
                        )
                );
    }

    private List<Map<String, String>> buildLegalSources(List<Document> documents) {
        List<Map<String, String>> sources = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return sources;
        }

        Set<String> seen = new HashSet<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            String rawName = "";
            if (meta != null) {
                if (meta.containsKey("original_filename") && meta.get("original_filename") != null) {
                    rawName = meta.get("original_filename").toString();
                } else if (meta.containsKey("file_name") && meta.get("file_name") != null) {
                    rawName = meta.get("file_name").toString();
                } else if (meta.containsKey("source") && meta.get("source") != null) {
                    rawName = meta.get("source").toString();
                }
            }

            if (rawName == null || rawName.isBlank()) {
                rawName = "Normativa de Tránsito";
            }

            String badge = rawName;
            String title = rawName;
            String lower = rawName.toLowerCase();

            if (lower.contains("reglamento")) {
                badge = "Reglamento LOTTTSV";
                title = "Reglamento General para la Aplicación de la LOTTTSV";
            } else if (lower.contains("coip")) {
                badge = "COIP Tránsito";
                title = "Código Orgánico Integral Penal (COIP)";
            } else if (lower.contains("lotttsv") || lower.contains("transporte") || lower.contains("transito")) {
                badge = "LOTTTSV";
                title = "Ley Orgánica de Transporte Terrestre, Tránsito y Seguridad Vial";
            } else if (lower.contains("trabajo")) {
                badge = "Código del Trabajo";
                title = "Código del Trabajo del Ecuador";
            } else if (lower.contains("constitucion") || lower.contains("constitución")) {
                badge = "Constitución";
                title = "Constitución de la República del Ecuador";
            } else {
                badge = rawName.replaceAll("(?i)\\.(pdf|docx?|txt)$", "");
                title = rawName;
            }

            String docText = doc.getText() != null ? doc.getText() : "";
            String articleFound = "";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:Arts?|Art[ií]culos?)\\.?\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(docText);
            if (m.find()) {
                articleFound = "Art. " + m.group(1);
            }

            String snippet = docText.replaceAll("\\s+", " ").trim();
            if (snippet.length() > 220) {
                int dot = snippet.indexOf('.', 120);
                if (dot != -1 && dot < 240) {
                    snippet = snippet.substring(0, dot + 1);
                } else {
                    snippet = snippet.substring(0, 220) + "...";
                }
            }

            String key = badge + "|" + articleFound;
            if (!seen.contains(key) && !snippet.isBlank()) {
                seen.add(key);
                Map<String, String> item = new HashMap<>();
                item.put("badge", badge);
                item.put("title", title);
                item.put("article", articleFound.isBlank() ? badge : articleFound);
                item.put("extract", snippet);
                sources.add(item);
            }
        }
        return sources;
    }
}
