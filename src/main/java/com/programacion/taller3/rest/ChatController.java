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
        this.chatClient = builder.defaultAdvisors(new SimpleLoggerAdvisor()).build();
        this.configuration = configuration;
    }

    @PostMapping(path = "/api/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@RequestBody ChatRequest request, @RequestHeader(value = "X-User-Id", required = false) String headerUserId) {

        var message = request.message();
        var conversationId = request.conversationId() != null ? request.conversationId() : UUID.randomUUID().toString();
        var userId = request.userId() != null && !request.userId().isBlank() ? request.userId() : (headerUserId != null && !headerUserId.isBlank() ? headerUserId : "invitado");

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

        // 3. Documentos adjuntos del usuario (hasta 15 chunks para cubrir informes periciales completos)
        List<Document> userFileDocs = retrieveUserSessionDocuments(sessionAttachedFiles, message);

        // 4. Recuperación de leyes oficiales con descomposición sintáctica, filtro matemático Coseno y puente de hechos
        List<Document> officialLawDocs = retrieveOfficialLawsSyntactically(effectiveQuery, userFileDocs);

        // 5. Construir fuentes legales de Qdrant
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
                String text = d.getText() != null ? d.getText() : "";
                contextBuilder.append(text).append("\n\n");
            }
            contextBuilder.append("</MARCO_LEGAL_OFICIAL_RAG>\n");
        }

        String context = contextBuilder.toString().trim();

        // 5. Formatear historial conversacional previo para memoria multi-turno dinámica
        String historySection = formatRecentHistory(previousMessages);

        String systemMessage;
        try {
            String template = new String(systemPrompt.getContentAsByteArray(), StandardCharsets.UTF_8);
            systemMessage = template.replace("{normativa}", context.isBlank() ? "No se encontraron fragmentos normativos para esta consulta." : context).replace("{historial}", historySection);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error cargando el archivo systemPrompt.st", e);
        }

        StringBuilder fullResponse = new StringBuilder();

        Flux<ServerSentEvent<String>> tokens = chatClient.prompt().system(systemMessage).user(message).stream().content().doOnNext(fullResponse::append).map(chunk -> ServerSentEvent.<String>builder().event("token").data(Base64.getEncoder().encodeToString(chunk.getBytes(StandardCharsets.UTF_8))).build());

        // 6. Preparar bloque de fuentes legales reales [LEGAL_SOURCES]
        String sourcesJson;
        try {
            sourcesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(realSources);
        } catch (Exception e) {
            sourcesJson = "[]";
        }
        final String finalSourcesJson = sourcesJson;

        Flux<ServerSentEvent<String>> sourcesFlux = Flux.defer(() -> {
            String answer = fullResponse.toString().toLowerCase();
            // Verificación de Grounding RAG Puro: solo se emiten fuentes si la respuesta generada
            // realmente cita artículos legales, emite el bloque de infracciones o menciona la ley consultada.
            boolean hasLegalGrounding = answer.contains("art.")
                    || answer.contains("artículo")
                    || answer.contains("articulo")
                    || answer.contains("[infraction_data]")
                    || realSources.stream().anyMatch(s -> {
                        String badge = s.getOrDefault("badge", "").toLowerCase().trim();
                        return !badge.isBlank() && answer.contains(badge);
                    });

            if (!hasLegalGrounding || finalSourcesJson.isBlank() || finalSourcesJson.equals("[]")) {
                return Flux.empty();
            }

            String sourcesChunk = "\n[LEGAL_SOURCES]" + finalSourcesJson + "[/LEGAL_SOURCES]";
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("token")
                    .data(Base64.getEncoder().encodeToString(sourcesChunk.getBytes(StandardCharsets.UTF_8)))
                    .build());
        });

        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            if (!fullResponse.isEmpty()) {
                try {
                    String toSave = fullResponse.toString();
                    String answer = toSave.toLowerCase();
                    boolean hasLegalGrounding = answer.contains("art.")
                            || answer.contains("artículo")
                            || answer.contains("articulo")
                            || answer.contains("[infraction_data]")
                            || realSources.stream().anyMatch(s -> {
                                String badge = s.getOrDefault("badge", "").toLowerCase().trim();
                                return !badge.isBlank() && answer.contains(badge);
                            });

                    if (hasLegalGrounding && !finalSourcesJson.isBlank() && !finalSourcesJson.equals("[]") && !toSave.contains("[LEGAL_SOURCES]")) {
                        toSave += "\n[LEGAL_SOURCES]" + finalSourcesJson + "[/LEGAL_SOURCES]";
                    }
                    conversationService.saveMessage(conversationId, "assistant", toSave);
                } catch (Exception e) {
                    System.err.println("Error guardando respuesta del asistente: " + e.getMessage());
                }
            }
            return Flux.just(ServerSentEvent.<String>builder().event("done").data("[DONE]").build());
        });

        return tokens.concatWith(sourcesFlux).concatWith(done).onErrorResume(error -> Flux.just(ServerSentEvent.<String>builder().event("error").data(error.getMessage()).build()));
    }

    /**
     * Recupera exclusivamente los fragmentos de los archivos subidos por el usuario en la sesión actual.
     * Utiliza un topK de 15 para abarcar informes periciales y documentos completos de hasta 15 páginas.
     */
    private List<Document> retrieveUserSessionDocuments(List<String> sessionAttachedFiles, String message) {
        if (sessionAttachedFiles == null || sessionAttachedFiles.isEmpty()) {
            return List.of();
        }
        List<Document> userFileDocs = new ArrayList<>();
        Set<String> seenChunkIds = new HashSet<>();

        for (String attachedFile : sessionAttachedFiles) {
            String safeFile = attachedFile.replace("'", "").trim();
            String safeFileClean = safeFile.replaceAll("(?i)\\.(pdf|docx|txt|jpg|png)$", "");
            String userDocQuery = (safeFile + " " + safeFileClean + " " + message).trim();
            List<Document> currentFileDocs = new ArrayList<>();

            // ESTRATEGIA 1: filtro exacto por nombre de archivo en Qdrant
            try {
                currentFileDocs = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(userDocQuery)
                        .topK(15)
                        .filterExpression("source == '" + safeFile + "' || file_name == '" + safeFile + "' || metadata.source == '" + safeFile + "' || metadata.file_name == '" + safeFile + "'")
                        .build());
            } catch (Exception e) {
                System.err.println("Filtro source fallo: " + e.getMessage());
            }

            // ESTRATEGIA 2: filtro seguro de respaldo que solo acepta chunks que coincidan con este archivo
            if (currentFileDocs.isEmpty()) {
                try {
                    List<Document> allResults = vectorStore.similaritySearch(SearchRequest.builder()
                            .query(userDocQuery)
                            .topK(40)
                            .build());
                    String finalSafeFile = safeFile.toLowerCase().trim();
                    String finalSafeClean = safeFileClean.toLowerCase().trim();
                    currentFileDocs = allResults.stream().filter(d -> {
                        Map<String, Object> meta = d.getMetadata();
                        if (meta == null) return false;
                        String src = meta.getOrDefault("source", "").toString().toLowerCase().trim();
                        String fn = meta.getOrDefault("file_name", "").toString().toLowerCase().trim();
                        String orig = meta.getOrDefault("original_filename", "").toString().toLowerCase().trim();
                        return finalSafeFile.equals(src) || finalSafeFile.equals(fn) || finalSafeFile.equals(orig)
                                || (src.contains(finalSafeClean) && !src.isBlank())
                                || (fn.contains(finalSafeClean) && !fn.isBlank());
                    }).collect(Collectors.toList());
                } catch (Exception e) {
                    System.err.println("Busqueda de respaldo por archivo fallo: " + e.getMessage());
                }
            }

            // ESTRATEGIA 3: Si todavía no encuentra (ej. consulta muy abstracta), buscar directamente por el nombre del archivo
            if (currentFileDocs.isEmpty() && !safeFileClean.isBlank()) {
                try {
                    List<Document> fileFallbackResults = vectorStore.similaritySearch(SearchRequest.builder()
                            .query(safeFileClean)
                            .topK(25)
                            .build());
                    String finalSafeFile = safeFile.toLowerCase().trim();
                    String finalSafeClean = safeFileClean.toLowerCase().trim();
                    currentFileDocs = fileFallbackResults.stream().filter(d -> {
                        Map<String, Object> meta = d.getMetadata();
                        if (meta == null) return false;
                        String src = meta.getOrDefault("source", "").toString().toLowerCase().trim();
                        String fn = meta.getOrDefault("file_name", "").toString().toLowerCase().trim();
                        String orig = meta.getOrDefault("original_filename", "").toString().toLowerCase().trim();
                        return finalSafeFile.equals(src) || finalSafeFile.equals(fn) || finalSafeFile.equals(orig)
                                || (src.contains(finalSafeClean) && !src.isBlank())
                                || (fn.contains(finalSafeClean) && !fn.isBlank());
                    }).collect(Collectors.toList());
                } catch (Exception e) {
                    System.err.println("Busqueda directa por nombre de archivo fallo: " + e.getMessage());
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
        return userFileDocs;
    }

    /**
     * Contextualiza la consulta si es una repregunta breve (< 80 caracteres),
     * vinculando temáticamente el último turno del usuario para que los embeddings mantengan el hilo.
     */
    private String buildEffectiveSearchQuery(String currentMessage, List<ChatMessage> previousMessages) {
        if (previousMessages == null || previousMessages.isEmpty()) {
            return currentMessage;
        }

        String trimmed = currentMessage.trim();
        // Si el mensaje es una micro-expresión (<= 7 caracteres, ej. "hola", "ok", "gracias"),
        // no arrastra el historial para no contaminar saludos o cortesías con temas previos
        if (trimmed.length() <= 7) {
            return trimmed;
        }

        if (trimmed.length() < 80) {
            for (int i = previousMessages.size() - 1; i >= 0; i--) {
                ChatMessage m = previousMessages.get(i);
                if ("user".equalsIgnoreCase(m.role()) && !m.content().equalsIgnoreCase(currentMessage)) {
                    String prev = m.content().trim();
                    if (!prev.isBlank()) {
                        return prev + " " + trimmed;
                    }
                }
            }
        }
        return currentMessage;
    }

    /**
     * Recupera leyes oficiales con Ventana Deslizante Léxica (Overlapping Word Sliding Window)
     * y filtro matemático Coseno adaptativo.
     * 100% agnóstico a palabras quemadas, ortografía o signos de puntuación.
     */
    private List<Document> retrieveOfficialLawsSyntactically(String effectiveQuery, List<Document> userFileDocs) {
        // Si la consulta es una micro-expresión sin documento adjunto (< 8 caracteres, ej. "hola", "ok"),
        // no realiza viajes innecesarios a Qdrant y retorna lista vacía de inmediato
        if (effectiveQuery == null || (effectiveQuery.trim().length() < 8 && userFileDocs.isEmpty())) {
            return List.of();
        }

        List<Document> combined = new ArrayList<>();
        Set<String> seenIdentifiers = new HashSet<>();

        List<String> subQueries = decomposeQuerySyntactically(effectiveQuery);
        // Si NO hay documento adjunto (consulta normal), threshold = 0.34 (filtra ruido semántico, saludos y temas ajenos).
        // Si SÍ hay documento adjunto, threshold = 0.55 para que preguntas como "resúmelo" o "de qué trata" devuelvan 0 leyes oficiales (evita contaminar con COGEP u otras normas).
        double threshold = userFileDocs.isEmpty() ? 0.34 : 0.55;

        for (int i = 0; i < subQueries.size(); i++) {
            // La primera búsqueda (índice 0) es la consulta global con topK amplio de 14 fragmentos;
            // las sub-ventanas deslizantes aportan 6 fragmentos especializados cada una.
            int topK = (i == 0) ? 14 : 6;
            addUniqueLawDocs(combined, seenIdentifiers, subQueries.get(i), topK, threshold);
        }

        // PUENTE AUTOMÁTICO DE HECHOS: Si hay un documento adjunto Y la pregunta del usuario superó el umbral legal
        // (es decir, pide sanciones/leyes sobre el documento), buscamos también en Qdrant usando los hechos del archivo
        if (!userFileDocs.isEmpty() && !combined.isEmpty()) {
            String docFactsQuery = userFileDocs.stream()
                    .map(d -> d.getText() != null ? d.getText().replaceAll("\\s+", " ").trim() : "")
                    .filter(t -> t.length() > 80)
                    .limit(3)
                    .collect(Collectors.joining(" "));

            if (docFactsQuery.isBlank() && !userFileDocs.isEmpty()) {
                String firstText = userFileDocs.get(0).getText();
                docFactsQuery = firstText != null ? firstText.replaceAll("\\s+", " ").trim() : "";
            }

            if (!docFactsQuery.isBlank()) {
                if (docFactsQuery.length() > 500) {
                    docFactsQuery = docFactsQuery.substring(0, 500);
                }
                addUniqueLawDocs(combined, seenIdentifiers, docFactsQuery, 6, threshold);
            }
        }

        System.out.println("Total fragmentos deduplicados para marco legal oficial: " + combined.size());
        return combined;
    }

    private void addUniqueLawDocs(List<Document> combined, Set<String> seenIdentifiers, String queryText, int topK, double threshold) {
        try {
            var searchBuilder = SearchRequest.builder()
                    .query(queryText)
                    .topK(topK);

            if (threshold > 0.0) {
                searchBuilder.similarityThreshold(threshold);
            }

            // Un solo viaje directo a Qdrant (evita doble cálculo de embeddings y doble latencia de red)
            List<Document> docs = vectorStore.similaritySearch(searchBuilder.build());

            if (docs != null) {
                for (Document d : docs) {
                    // Excluir cualquier documento del usuario que pudiera colarse
                    Map<String, Object> meta = d.getMetadata();
                    if (meta != null && "usuario_adjunto".equalsIgnoreCase(String.valueOf(meta.get("tipo")))) {
                        continue;
                    }

                    String docText = d.getText() != null ? d.getText().trim() : "";
                    String snippetKey = docText.length() > 60 ? docText.substring(0, 60) : docText;
                    String idKey = d.getId() != null ? d.getId() : snippetKey;

                    if (seenIdentifiers.add(idKey)) {
                        combined.add(d);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error en búsqueda vectorial directa [" + queryText + "]: " + e.getMessage());
        }
    }

    /**
     * Descomposición mediante Ventana Deslizante Léxica con Solapamiento (Overlapping Word Sliding Window).
     * 100% agnóstico a palabras quemadas, ortografía o signos de puntuación del usuario.
     */
    private List<String> decomposeQuerySyntactically(String query) {
        if (query == null || query.isBlank()) return List.of();
        String cleanQuery = query.trim().replaceAll("\\s+", " ");

        String[] words = cleanQuery.split(" ");

        // Si la consulta es corta (16 palabras o menos), la búsqueda global única es suficiente
        if (words.length <= 16) {
            return List.of(cleanQuery);
        }

        List<String> queries = new ArrayList<>();
        // 1. Siempre incluimos primero la consulta global completa (captura el contexto general)
        queries.add(cleanQuery);

        // 2. Ventana deslizante de 12 palabras con paso (stride) de 8 palabras (solapamiento de 4 palabras)
        // Permite hasta 5 consultas y garantiza siempre la inclusión de la cola final de la pregunta
        int windowSize = 12;
        int stride = 8;

        for (int start = 0; start < words.length && queries.size() < 5; start += stride) {
            int end = Math.min(start + windowSize, words.length);
            if (end - start < 7 && start > 0) {
                start = Math.max(0, words.length - windowSize);
                end = words.length;
            }

            String windowChunk = String.join(" ", Arrays.copyOfRange(words, start, end)).trim();
            if (!windowChunk.equalsIgnoreCase(cleanQuery) && !queries.contains(windowChunk) && windowChunk.length() >= 20) {
                queries.add(windowChunk);
            }

            if (end == words.length) {
                break;
            }
        }

        // Garantizar que la ventana final (la cola de la pregunta, e.g. "llantas lisas") siempre esté presente
        if (words.length > windowSize) {
            int tailStart = Math.max(0, words.length - windowSize);
            String tailChunk = String.join(" ", Arrays.copyOfRange(words, tailStart, words.length)).trim();
            if (!tailChunk.equalsIgnoreCase(cleanQuery) && !queries.contains(tailChunk) && tailChunk.length() >= 15) {
                queries.add(tailChunk);
            }
        }

        return queries;
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
            String cleanContent = msg.content().replaceAll("\\[INFRACTION_DATA\\][\\s\\S]*?\\[/INFRACTION_DATA\\]", "").replaceAll("\\[LEGAL_SOURCES\\][\\s\\S]*?\\[/LEGAL_SOURCES\\]", "").trim();
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
