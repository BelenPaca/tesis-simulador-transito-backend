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
        var searchRequest = SearchRequest.builder()
                .query(message)
                .topK(3)
                .filterExpression("categoria == 'documento'")
                .build();
        var documents = vectorStore.similaritySearch(searchRequest);
        String context = documents.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);

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

        // Cuando el stream termine, guardar la respuesta del asistente en PostgreSQL para la UI
        Flux<ServerSentEvent<String>> done = Flux.defer(() -> {
            if (!fullResponse.isEmpty()) {
                try {
                    conversationService.saveMessage(conversationId, "assistant", fullResponse.toString());
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

        return tokens.concatWith(done)
                .onErrorResume(error -> Flux.just(
                                ServerSentEvent.<String>builder()
                                        .event("error")
                                        .data(error.getMessage())
                                        .build()
                        )
                );
    }
}
