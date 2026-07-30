package com.programacion.taller3.services;

import com.programacion.taller3.entities.ChatMessageEntity;
import com.programacion.taller3.entities.ConversationEntity;
import com.programacion.taller3.models.ChatMessage;
import com.programacion.taller3.models.Conversation;
import com.programacion.taller3.repositories.ChatMessageRepository;
import com.programacion.taller3.repositories.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Servicio para gestionar conversaciones persistentes en PostgreSQL.
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;

    public ConversationService(ConversationRepository conversationRepository, ChatMessageRepository chatMessageRepository) {
        this.conversationRepository = conversationRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    /**
     * Crea una nueva conversación en PostgreSQL.
     */
    @Transactional
    public Conversation createConversation(String id, String title) {
        LocalDateTime createdAt = LocalDateTime.now();

        if (title == null || title.isBlank()) {
            title = "Nueva conversación";
        }

        ConversationEntity entity = new ConversationEntity(id, title, createdAt);
        conversationRepository.save(entity);

        return new Conversation(id, title, createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), List.of());
    }

    /**
     * Lista todas las conversaciones guardadas.
     */
    public List<Conversation> listConversations() {
        return conversationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(entity -> new Conversation(
                        entity.getId(),
                        entity.getTitle(),
                        entity.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        List.of()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Guarda un mensaje en PostgreSQL.
     * Si es el primer mensaje del usuario, auto-crea la conversación.
     */
    @Transactional
    public void saveMessage(String conversationId, String role, String content) {
        // Auto-crear conversación si es el primer mensaje del usuario
        if ("user".equals(role)) {
            ensureConversationExists(conversationId, content);
        }

        ChatMessageEntity msg = new ChatMessageEntity(
                conversationId,
                role,
                content,
                LocalDateTime.now()
        );
        chatMessageRepository.save(msg);
    }

    /**
     * Obtiene todos los mensajes de una conversación, ordenados cronológicamente.
     */
    public List<ChatMessage> getMessages(String conversationId) {
        return chatMessageRepository.findByConversationIdOrderByTimestampAsc(conversationId).stream()
                .map(entity -> new ChatMessage(
                        entity.getRole(),
                        entity.getContent(),
                        entity.getTimestamp().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ))
                .collect(Collectors.toList());
    }

    /**
     * Elimina una conversación y todos sus mensajes de PostgreSQL.
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        chatMessageRepository.deleteByConversationId(conversationId);
        conversationRepository.deleteById(conversationId);
    }

    /**
     * Verifica si la conversación existe; si no, la crea con el primer mensaje como título.
     */
    private void ensureConversationExists(String conversationId, String firstMessage) {
        if (!conversationRepository.existsById(conversationId)) {
            String title = firstMessage.length() > 50
                    ? firstMessage.substring(0, 50) + "..."
                    : firstMessage;
            createConversation(conversationId, title);
        }
    }
}
