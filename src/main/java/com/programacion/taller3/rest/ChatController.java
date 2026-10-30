package com.programacion.taller3.rest;

import com.programacion.taller3.models.ChatMessage;
import com.programacion.taller3.services.ConversationService;
import org.apache.camel.spi.RestConfiguration;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    public ChatController(ChatClient.Builder builder, RestConfiguration configuration) {
        this.chatClient = builder
                .defaultAdvisors(
                        new SimpleLoggerAdvisor()
                )
                .build();
        this.configuration = configuration;
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

        // 1. Obtener historial previo de PostgreSQL ANTES de guardar el mensaje actual
        List<ChatMessage> previousMessages = conversationService.getMessages(conversationId);

        // Guardar mensaje del usuario en PostgreSQL para la UI
        conversationService.saveMessage(conversationId, "user", message, userId);

        // 2. Determinar la consulta efectiva para Qdrant (contextualización dinámica si es pregunta de seguimiento)
        String effectiveQuery = buildEffectiveSearchQuery(message, previousMessages);

        // 3. Separación estricta de ámbitos: Documentos adjuntos del usuario vs Leyes Oficiales
        String attachedFile = request.attachedFileName();
        List<Document> userFileDocs = new ArrayList<>();
        List<Document> officialLawDocs = new ArrayList<>();

        if (attachedFile != null && !attachedFile.isBlank()) {
            String safeFile = attachedFile.replace("'", "");
            String userDocQuery = message.length() < 20 ? (safeFile + " " + message) : message;

            // ESTRATEGIA 1: filtro exacto por nombre de archivo en Qdrant
            try {
                userFileDocs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(userDocQuery)
                        .topK(10)
                        .filterExpression("source == '" + safeFile + "' || file_name == '" + safeFile + "'")
                        .build());
                System.out.println("Busqueda doc usuario (filtro source): " + userFileDocs.size() + " resultados");
            } catch (Exception e) {
                System.err.println("Filtro source fallo: " + e.getMessage());
            }

            // ESTRATEGIA 2: filtro por categoria=documento
            if (userFileDocs.isEmpty()) {
                try {
                    userFileDocs = vectorStore.similaritySearch(SearchRequest.builder()
                            .query(userDocQuery)
                            .topK(10)
                            .filterExpression("categoria == 'documento'")
                            .build());
                    System.out.println("Busqueda doc usuario (filtro categoria): " + userFileDocs.size() + " resultados");
                } catch (Exception e) {
                    System.err.println("Filtro categoria fallo: " + e.getMessage());
                }
            }

            // ESTRATEGIA 3: sin filtro Qdrant - buscar amplio y filtrar en memoria por metadatos.
            // Cubre el caso donde Qdrant Cloud no tiene payload indexes en source/file_name/tipo/categoria.
            if (userFileDocs.isEmpty()) {
                try {
                    List<Document> allResults = vectorStore.similaritySearch(SearchRequest.builder()
                            .query(userDocQuery)
                            .topK(20)
                            .build());
                    String finalSafeFile = safeFile;
                    userFileDocs = allResults.stream()
                            .filter(d -> {
                                Map<String, Object> meta = d.getMetadata();
                                if (meta == null) return false;
                                String tipo = meta.getOrDefault("tipo", "").toString();
                                String cat  = meta.getOrDefault("categoria", "").toString();
                                String src  = meta.getOrDefault("source", "").toString();
                                String fn   = meta.getOrDefault("file_name", "").toString();
                                return "usuario_adjunto".equalsIgnoreCase(tipo)
                                        || "documento".equalsIgnoreCase(cat)
                                        || finalSafeFile.equalsIgnoreCase(src)
                                        || finalSafeFile.equalsIgnoreCase(fn);
                            })
                            .collect(Collectors.toList());
                    System.out.println("Busqueda doc usuario (filtro en memoria): " + userFileDocs.size() + " resultados");
                } catch (Exception e) {
                    System.err.println("Busqueda sin filtro fallo: " + e.getMessage());
                }
            }

            // Solo buscar leyes oficiales complementarias si la consulta no es una inspección pura del documento
            boolean isPureDocInspection = isDocumentInspectionQuery(message);
            if (!isPureDocInspection) {
                try {
                    var searchLaws = SearchRequest.builder()
                            .query(effectiveQuery)
                            .topK(5)
                            .filterExpression("tipo == 'ley_oficial'")
                            .build();
                    officialLawDocs = vectorStore.similaritySearch(searchLaws);
                } catch (Exception e) {
                    System.err.println("Nota en búsqueda de leyes oficiales filtradas: " + e.getMessage());
                }
            }
        } else {
            // Consulta normal: Solo leyes oficiales
            try {
                var searchRequest = SearchRequest.builder()
                        .query(effectiveQuery)
                        .topK(6)
                        .filterExpression("tipo == 'ley_oficial'")
                        .build();
                officialLawDocs = vectorStore.similaritySearch(searchRequest);
            } catch (Exception e) {
                System.err.println("Nota en búsqueda de leyes oficiales: " + e.getMessage());
            }

            if (officialLawDocs.isEmpty()) {
                officialLawDocs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(effectiveQuery)
                        .topK(6)
                        .build());
            }
        }

        // 4. Construir fuentes legales de Qdrant
        List<Map<String, String>> realSources = buildLegalSources(officialLawDocs);

        // Construir contexto organizado por secciones
        StringBuilder contextBuilder = new StringBuilder();
        if (!userFileDocs.isEmpty()) {
            contextBuilder.append("=== DOCUMENTO DEL CASO ADJUNTADO POR EL USUARIO (").append(attachedFile).append(") ===\n");
            for (Document d : userFileDocs) {
                contextBuilder.append(d.getText()).append("\n\n");
            }
        }

        if (!officialLawDocs.isEmpty()) {
            contextBuilder.append("=== NORMATIVA LEGAL OFICIAL DEL ECUADOR ===\n");
            for (Document d : officialLawDocs) {
                contextBuilder.append(d.getText()).append("\n\n");
            }
        }

        String context = contextBuilder.toString().trim();

        // 5. Formatear historial conversacional previo para memoria multi-turno dinámica
        String historySection = formatRecentHistory(previousMessages);

        String systemMessage;
        try {
            String template = new String(systemPrompt.getContentAsByteArray(), StandardCharsets.UTF_8);
            systemMessage = template
                    .replace("{normativa}", context.isBlank() ? "No se encontraron fragmentos normativos para esta consulta." : context)
                    .replace("{historial}", historySection);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error cargando el archivo systemPrompt.st", e);
        }

        StringBuilder fullResponse = new StringBuilder();

        Flux<ServerSentEvent<String>> tokens = chatClient.prompt()
                .system(systemMessage)
                .user(message)
                .stream()
                .content()
                .doOnNext(fullResponse::append)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("token")
                        .data(Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8)))
                        .build()
                );

        // 6. Preparar bloque de fuentes legales reales [LEGAL_SOURCES]
        String sourcesJson;
        try {
            sourcesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(realSources);
        } catch (Exception e) {
            sourcesJson = "[]";
        }
        final String finalSourcesJson = sourcesJson;

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

    private String buildEffectiveSearchQuery(String currentMessage, List<ChatMessage> previousMessages) {
        if (previousMessages == null || previousMessages.isEmpty()) {
            return currentMessage;
        }

        if (isFollowUpQuestion(currentMessage)) {
            // Localizar la última pregunta temática formulada por el usuario
            for (int i = previousMessages.size() - 1; i >= 0; i--) {
                ChatMessage m = previousMessages.get(i);
                if ("user".equalsIgnoreCase(m.role()) && !m.content().equalsIgnoreCase(currentMessage)) {
                    String prev = m.content().trim();
                    if (!prev.isBlank()) {
                        return prev + " " + currentMessage;
                    }
                }
            }
        }
        return currentMessage;
    }

    private boolean isDocumentInspectionQuery(String text) {
        if (text == null) return false;
        String clean = text.toLowerCase().trim();
        return clean.contains("de que habla") ||
               clean.contains("de qué habla") ||
               clean.contains("resumen") ||
               clean.contains("resume") ||
               clean.contains("que contiene") ||
               clean.contains("qué contiene") ||
               clean.contains("analiza este documento") ||
               clean.contains("analiza el documento") ||
               clean.contains("de que trata") ||
               clean.contains("de qué trata") ||
               clean.contains("explica el documento") ||
               clean.contains("contenido del documento");
    }

    private boolean isFollowUpQuestion(String text) {
        if (text == null) return false;
        String clean = text.toLowerCase().trim();
        if (clean.length() <= 35) return true;
        return clean.contains("punto") ||
               clean.contains("numeral") ||
               clean.contains("artículo") ||
               clean.contains("articulo") ||
               clean.contains("inciso") ||
               clean.contains("paso") ||
               clean.contains("anterior") ||
               clean.contains("primero") ||
               clean.contains("segundo") ||
               clean.contains("tercero") ||
               clean.contains("cuarto") ||
               clean.contains("quinto") ||
               clean.contains("sexto") ||
               clean.contains("séptimo") ||
               clean.contains("septimo") ||
               clean.contains("octavo") ||
               clean.contains("noveno") ||
               clean.contains("décimo") ||
               clean.contains("decimo") ||
               clean.contains("sanción") ||
               clean.contains("sancion") ||
               clean.contains("multa") ||
               clean.contains("y eso") ||
               clean.contains("por qué") ||
               clean.contains("por que") ||
               clean.contains("qué significa") ||
               clean.contains("que significa") ||
               clean.contains("explica más") ||
               clean.contains("explícame más");
    }

    private String formatRecentHistory(List<ChatMessage> previousMessages) {
        if (previousMessages == null || previousMessages.isEmpty()) {
            return "";
        }

        int maxHistory = 6;
        int start = Math.max(0, previousMessages.size() - maxHistory);
        StringBuilder sb = new StringBuilder();
        sb.append("HISTORIAL DE LA CONVERSACIÓN PREVIA (ÚLTIMOS TURNOS):\n");

        for (int i = start; i < previousMessages.size(); i++) {
            ChatMessage msg = previousMessages.get(i);
            String roleName = "user".equalsIgnoreCase(msg.role()) ? "Usuario" : "TransiAI (Asistente)";
            String cleanContent = msg.content()
                    .replaceAll("\\[INFRACTION_DATA\\][\\s\\S]*?\\[/INFRACTION_DATA\\]", "")
                    .replaceAll("\\[LEGAL_SOURCES\\][\\s\\S]*?\\[/LEGAL_SOURCES\\]", "")
                    .trim();
            if (!cleanContent.isBlank()) {
                sb.append(roleName).append(": ").append(cleanContent).append("\n\n");
            }
        }
        sb.append("--------------------------------------------------\n");
        return sb.toString();
    }

    private String extractRawFilename(Map<String, Object> meta) {
        if (meta == null) return "";
        if (meta.containsKey("original_filename") && meta.get("original_filename") != null) {
            return meta.get("original_filename").toString();
        } else if (meta.containsKey("file_name") && meta.get("file_name") != null) {
            return meta.get("file_name").toString();
        } else if (meta.containsKey("source") && meta.get("source") != null) {
            return meta.get("source").toString();
        }
        return "";
    }

    private String resolveLegalBadge(String rawName) {
        if (rawName == null) return "Normativa Legal";
        String lower = rawName.toLowerCase();
        if (lower.contains("reglamento")) {
            return "Reglamento LOTTTSV";
        } else if (lower.contains("coip")) {
            return "COIP Tránsito";
        } else if (lower.contains("transporte") || lower.contains("transito") || lower.contains("tránsito") || lower.contains("lotttsv")) {
            return "LOTTTSV";
        } else if (lower.contains("trabajo") || lower.contains("ct.md") || lower.contains("_ct")) {
            return "Código del Trabajo";
        } else if (lower.contains("procesos") || lower.contains("cogep") || lower.contains("_co.md") || lower.contains("_co")) {
            return "COGEP";
        } else if (lower.contains("constituc") || lower.contains("cre.md") || lower.contains("_cre")) {
            return "Constitución";
        } else {
            return rawName.replace(".md", "").replace(".txt", "").replaceAll("[^a-zA-Z0-9 ]", " ").trim();
        }
    }

    private String resolveLegalTitle(String badge) {
        return switch (badge) {
            case "Reglamento LOTTTSV" -> "Reglamento General para la Aplicación de la LOTTTSV";
            case "COIP Tránsito" -> "Código Orgánico Integral Penal (COIP)";
            case "LOTTTSV" -> "Ley Orgánica de Transporte Terrestre, Tránsito y Seguridad Vial";
            case "Código del Trabajo" -> "Código del Trabajo del Ecuador";
            case "COGEP" -> "Código Orgánico General de Procesos (COGEP)";
            case "Constitución" -> "Constitución de la República del Ecuador";
            default -> badge;
        };
    }

    private List<Map<String, String>> buildLegalSources(List<Document> documents) {
        List<Map<String, String>> sources = new ArrayList<>();
        if (documents == null || documents.isEmpty()) {
            return sources;
        }

        Set<String> seenBadges = new HashSet<>();
        for (Document doc : documents) {
            Map<String, Object> meta = doc.getMetadata();
            if (meta != null) {
                Object tipoObj = meta.get("tipo");
                if (tipoObj != null && "usuario_adjunto".equalsIgnoreCase(tipoObj.toString())) {
                    continue; // Jamás generar pastillas para documentos adjuntos del usuario
                }
            }

            String rawName = extractRawFilename(meta);
            if (rawName == null || rawName.isBlank() || rawName.toLowerCase().startsWith("upload_") || rawName.toLowerCase().endsWith(".tmp")) {
                continue;
            }

            String badge = resolveLegalBadge(rawName);
            if (seenBadges.contains(badge)) {
                continue;
            }
            seenBadges.add(badge);

            String title = resolveLegalTitle(badge);

            String docText = doc.getText() != null ? doc.getText() : "";
            String articleFound = "";
            Matcher m = Pattern.compile("(?:Arts?|Art[ií]culos?)\\.?\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(docText);
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

            Map<String, String> item = new HashMap<>();
            item.put("badge", badge);
            item.put("title", title);
            item.put("article", articleFound.isBlank() ? badge : articleFound);
            item.put("extract", snippet);
            sources.add(item);
        }
        return sources;
    }
}
