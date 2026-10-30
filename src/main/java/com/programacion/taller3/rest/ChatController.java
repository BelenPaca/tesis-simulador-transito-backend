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

        // Si viene un archivo adjunto en este turno, registrarlo/acumularlo en la conversación en Neon
        if (request.attachedFileName() != null && !request.attachedFileName().isBlank()) {
            conversationService.registerAttachedFile(conversationId, request.attachedFileName());
        }

        // 2. Determinar la consulta efectiva para Qdrant (contextualización dinámica si es pregunta de seguimiento)
        String effectiveQuery = buildEffectiveSearchQuery(message, previousMessages);

        // 3. Separación estricta de ámbitos: Documentos adjuntos del usuario vs Leyes Oficiales
        // Recuperar archivos acumulados en esta conversación (soporte multi-documento y persistencia multi-turno)
        List<String> sessionAttachedFiles = conversationService.getAttachedFiles(conversationId);
        if (sessionAttachedFiles.isEmpty() && request.attachedFileName() != null && !request.attachedFileName().isBlank()) {
            sessionAttachedFiles = List.of(request.attachedFileName().trim());
        }

        List<Document> userFileDocs = new ArrayList<>();
        List<Document> officialLawDocs = new ArrayList<>();

        if (!sessionAttachedFiles.isEmpty()) {
            // Buscar fragmentos para cada uno de los archivos vinculados a la conversación
            Set<String> seenChunkIds = new HashSet<>();
            for (String attachedFile : sessionAttachedFiles) {
                String safeFile = attachedFile.replace("'", "");
                String userDocQuery = message.length() < 20 ? (safeFile + " " + message) : message;
                List<Document> currentFileDocs = new ArrayList<>();

                // ESTRATEGIA 1: filtro exacto por nombre de archivo en Qdrant
                try {
                    currentFileDocs = vectorStore.similaritySearch(SearchRequest.builder()
                            .query(userDocQuery)
                            .topK(10)
                            .filterExpression("source == '" + safeFile + "' || file_name == '" + safeFile + "'")
                            .build());
                } catch (Exception e) {
                    System.err.println("Filtro source fallo: " + e.getMessage());
                }

                // ESTRATEGIA 2: filtro por categoria=documento
                if (currentFileDocs.isEmpty()) {
                    try {
                        currentFileDocs = vectorStore.similaritySearch(SearchRequest.builder()
                                .query(userDocQuery)
                                .topK(10)
                                .filterExpression("categoria == 'documento'")
                                .build());
                    } catch (Exception e) {
                        System.err.println("Filtro categoria fallo: " + e.getMessage());
                    }
                }

                // ESTRATEGIA 3: sin filtro Qdrant - buscar amplio y filtrar en memoria por metadatos
                if (currentFileDocs.isEmpty()) {
                    try {
                        List<Document> allResults = vectorStore.similaritySearch(SearchRequest.builder()
                                .query(userDocQuery)
                                .topK(20)
                                .build());
                        String finalSafeFile = safeFile;
                        currentFileDocs = allResults.stream()
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
                    } catch (Exception e) {
                        System.err.println("Busqueda sin filtro fallo: " + e.getMessage());
                    }
                }

                for (Document d : currentFileDocs) {
                    if (d.getId() != null && seenChunkIds.add(d.getId())) {
                        userFileDocs.add(d);
                    } else if (d.getId() == null) {
                        userFileDocs.add(d);
                    }
                }
            }
            System.out.println("Total fragmentos recuperados para documentos de la sesión: " + userFileDocs.size());

            // Solo buscar leyes oficiales complementarias si la consulta no es una inspección pura del documento
            boolean isPureDocInspection = isDocumentInspectionQuery(message);
            if (!isPureDocInspection) {
                officialLawDocs = retrieveOfficialLawsWithDecomposition(effectiveQuery);
            }
        } else {
            // Consulta normal: Búsqueda de leyes oficiales con Query Decomposition y deduplicación
            officialLawDocs = retrieveOfficialLawsWithDecomposition(effectiveQuery);
        }

        // 4. Construir fuentes legales de Qdrant
        List<Map<String, String>> realSources = buildLegalSources(officialLawDocs);

        // Construir contexto organizado por secciones claramente delimitadas
        StringBuilder contextBuilder = new StringBuilder();
        if (!userFileDocs.isEmpty()) {
            contextBuilder.append("<DOCUMENTOS_DEL_CASO_USUARIO>\n");
            String filesHeader = sessionAttachedFiles.isEmpty() ? "Documento del usuario" : String.join(", ", sessionAttachedFiles);
            contextBuilder.append("Archivos adjuntos del caso: ").append(filesHeader).append("\n\n");
            for (Document d : userFileDocs) {
                contextBuilder.append(d.getText()).append("\n\n");
            }
            contextBuilder.append("</DOCUMENTOS_DEL_CASO_USUARIO>\n\n");
        }

        if (!officialLawDocs.isEmpty()) {
            contextBuilder.append("<MARCO_LEGAL_OFICIAL_RAG>\n");
            for (Document d : officialLawDocs) {
                contextBuilder.append(d.getText()).append("\n\n");
            }
            contextBuilder.append("</MARCO_LEGAL_OFICIAL_RAG>\n");
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

    /**
     * Descompone consultas compuestas en sub-consultas enfocadas y busca en Qdrant
     * deduplicando fragmentos por ID y texto para garantizar cobertura completa (alcohol, velocidad, celular, etc.).
     */
    private List<Document> retrieveOfficialLawsWithDecomposition(String effectiveQuery) {
        List<String> subQueries = decomposeQueryIfCompound(effectiveQuery);
        List<Document> combined = new ArrayList<>();
        Set<String> seenIdentifiers = new HashSet<>();

        if (subQueries.size() <= 1) {
            // Consulta simple o directa: Búsqueda tradicional con topK amplio
            try {
                var search = SearchRequest.builder()
                        .query(effectiveQuery)
                        .topK(10)
                        .filterExpression("tipo == 'ley_oficial'")
                        .build();
                List<Document> docs = vectorStore.similaritySearch(search);
                if (docs.isEmpty()) {
                    docs = vectorStore.similaritySearch(SearchRequest.builder().query(effectiveQuery).topK(10).build());
                }
                return docs;
            } catch (Exception e) {
                System.err.println("Fallo búsqueda simple de leyes oficiales: " + e.getMessage());
                try {
                    return vectorStore.similaritySearch(SearchRequest.builder().query(effectiveQuery).topK(10).build());
                } catch (Exception ex) {
                    return List.of();
                }
            }
        }

        // Consulta compuesta: Ejecutar búsqueda independiente por cada intención
        System.out.println("Consulta compuesta detectada. Ejecutando Query Decomposition con " + subQueries.size() + " sub-consultas: " + subQueries);
        for (String subQ : subQueries) {
            try {
                var search = SearchRequest.builder()
                        .query(subQ)
                        .topK(4)
                        .filterExpression("tipo == 'ley_oficial'")
                        .build();
                List<Document> docs = vectorStore.similaritySearch(search);
                if (docs.isEmpty()) {
                    docs = vectorStore.similaritySearch(SearchRequest.builder().query(subQ).topK(4).build());
                }

                for (Document d : docs) {
                    String docText = d.getText() != null ? d.getText().trim() : "";
                    String snippetKey = docText.length() > 60 ? docText.substring(0, 60) : docText;
                    String idKey = d.getId() != null ? d.getId() : snippetKey;

                    if (seenIdentifiers.add(idKey)) {
                        combined.add(d);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error en sub-consulta [" + subQ + "]: " + e.getMessage());
            }
        }

        // Fallback de seguridad: Si por alguna razón la descomposición no trajo suficientes documentos, complementar con búsqueda directa
        if (combined.size() < 4) {
            try {
                List<Document> fallbackDocs = vectorStore.similaritySearch(SearchRequest.builder().query(effectiveQuery).topK(10).build());
                for (Document d : fallbackDocs) {
                    String docText = d.getText() != null ? d.getText().trim() : "";
                    String snippetKey = docText.length() > 60 ? docText.substring(0, 60) : docText;
                    String idKey = d.getId() != null ? d.getId() : snippetKey;
                    if (seenIdentifiers.add(idKey)) {
                        combined.add(d);
                    }
                }
            } catch (Exception ex) {
                System.err.println("Fallback general de leyes: " + ex.getMessage());
            }
        }

        System.out.println("Total fragmentos deduplicados para marco legal oficial: " + combined.size());
        return combined;
    }

    /**
     * Identifica si la consulta combina múltiples temas legales y la divide en sub-consultas enfocadas.
     */
    private List<String> decomposeQueryIfCompound(String query) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();

        // Detectar si contiene múltiples intenciones o escenarios
        boolean hasAlcohol = lower.contains("alcohol") || lower.contains("embriaguez") || lower.contains("ebrio") || lower.contains("g/l") || lower.contains("aliento");
        boolean hasVelocidad = lower.contains("velocidad") || lower.contains("km/h") || lower.contains("rango moderado") || lower.contains("radar") || lower.contains("carretera");
        boolean hasCelular = lower.contains("celular") || lower.contains("teléfono") || lower.contains("telefono") || lower.contains("móvil") || lower.contains("movil") || lower.contains("dispositivo");
        boolean hasSemaforo = lower.contains("semáforo") || lower.contains("semaforo") || lower.contains("luz roja");
        boolean hasLicencia = lower.contains("licencia") || lower.contains("sin licencia") || lower.contains("caducada");

        int scenarios = (hasAlcohol ? 1 : 0) + (hasVelocidad ? 1 : 0) + (hasCelular ? 1 : 0) + (hasSemaforo ? 1 : 0) + (hasLicencia ? 1 : 0);

        if (scenarios >= 2) {
            List<String> list = new ArrayList<>();
            if (hasAlcohol) list.add("sancion escala nivel de alcohol conductores estado embriaguez Art 385 COIP");
            if (hasVelocidad) list.add("limite de velocidad rango moderado fuera de rango carreteras Art 386 389 COIP Art 191 Reglamento");
            if (hasCelular) list.add("contravencion uso de telefono celular dispositivos moviles al conducir Art 391 COIP");
            if (hasSemaforo) list.add("contravencion desobedecer luz roja semaforo agente transito COIP");
            if (hasLicencia) list.add("conducir sin haber obtenido licencia o con licencia caducada COIP");
            return list;
        }

        // Si contiene conjunciones o numeraciones explícitas (1., 2., 3. o 'además', 'también')
        if (query.contains("1.") && query.contains("2.")) {
            String[] parts = query.split("\\b(?:1\\.|2\\.|3\\.|4\\.)\\s*");
            List<String> cleanParts = new ArrayList<>();
            for (String p : parts) {
                String trimmed = p.replaceAll("[\n,;]+", " ").trim();
                if (trimmed.length() >= 10) {
                    cleanParts.add(trimmed);
                }
            }
            if (cleanParts.size() >= 2) {
                return cleanParts.subList(0, Math.min(cleanParts.size(), 4));
            }
        }

        return List.of(query);
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
