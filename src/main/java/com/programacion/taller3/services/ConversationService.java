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
        return createConversation(id, title, null);
    }

    @Transactional
    public Conversation createConversation(String id, String title, String userId) {
        LocalDateTime createdAt = LocalDateTime.now();

        if (title == null || title.isBlank()) {
            title = "Nueva conversación";
        }

        ConversationEntity entity = new ConversationEntity(id, title, createdAt, userId);
        conversationRepository.save(entity);

        return new Conversation(id, title, createdAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), List.of(), userId);
    }

    /**
     * Lista todas las conversaciones activas guardadas.
     */
    public List<Conversation> listConversations() {
        return conversationRepository.findActiveConversations().stream()
                .map(entity -> new Conversation(
                        entity.getId(),
                        entity.getTitle(),
                        entity.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        List.of(),
                        entity.getUserId()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Lista las conversaciones activas de un usuario específico (correo o guest_id).
     */
    public List<Conversation> listConversations(String userId) {
        if (userId == null || userId.isBlank()) {
            return listConversations();
        }
        return conversationRepository.findActiveByUserId(userId).stream()
                .map(entity -> new Conversation(
                        entity.getId(),
                        entity.getTitle(),
                        entity.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        List.of(),
                        entity.getUserId()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Guarda un mensaje en PostgreSQL.
     * Si es el primer mensaje del usuario, auto-crea la conversación.
     */
    @Transactional
    public void saveMessage(String conversationId, String role, String content) {
        saveMessage(conversationId, role, content, null);
    }

    @Transactional
    public void saveMessage(String conversationId, String role, String content, String userId) {
        // Auto-crear conversación si es el primer mensaje del usuario
        if ("user".equals(role)) {
            ensureConversationExists(conversationId, content, userId);
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
     * Borrado lógico: Marca la conversación como inactiva (active = false).
     * Los mensajes y el registro permanecen en PostgreSQL para auditoría y tesis.
     */
    @Transactional
    public void deleteConversation(String conversationId) {
        conversationRepository.findById(conversationId).ifPresent(entity -> {
            entity.setActive(false);
            conversationRepository.save(entity);
        });
    }

    /**
     * Borrado lógico: Marca todas las conversaciones como inactivas.
     */
    @Transactional
    public void deleteAllConversations() {
        List<ConversationEntity> all = conversationRepository.findAll();
        for (ConversationEntity c : all) {
            c.setActive(false);
        }
        conversationRepository.saveAll(all);
    }

    /**
     * Borrado lógico: Marca todas las conversaciones del usuario como inactivas.
     */
    @Transactional
    public void deleteAllConversations(String userId) {
        if (userId == null || userId.isBlank()) {
            deleteAllConversations();
            return;
        }
        List<ConversationEntity> userConvs = conversationRepository.findByUserIdOrderByCreatedAtDesc(userId);
        for (ConversationEntity c : userConvs) {
            c.setActive(false);
        }
        conversationRepository.saveAll(userConvs);
    }

    /**
     * Registra o acumula un archivo adjunto en la conversación sin sobrescribir los anteriores.
     */
    @Transactional
    public void registerAttachedFile(String conversationId, String newFile) {
        if (conversationId == null || newFile == null || newFile.isBlank()) return;
        conversationRepository.findById(conversationId).ifPresent(entity -> {
            String current = entity.getAttachedFiles();
            if (current == null || current.isBlank()) {
                entity.setAttachedFiles(newFile.trim());
            } else {
                List<String> files = new java.util.ArrayList<>(List.of(current.split(",")));
                boolean alreadyPresent = files.stream().anyMatch(f -> f.trim().equalsIgnoreCase(newFile.trim()));
                if (!alreadyPresent) {
                    files.add(newFile.trim());
                    entity.setAttachedFiles(String.join(",", files));
                }
            }
            conversationRepository.save(entity);
        });
    }

    /**
     * Obtiene la lista de todos los archivos adjuntos vinculados históricamente a la conversación.
     */
    public List<String> getAttachedFiles(String conversationId) {
        if (conversationId == null) return List.of();
        return conversationRepository.findById(conversationId)
                .map(ConversationEntity::getAttachedFiles)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> java.util.Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(f -> !f.isEmpty())
                        .collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * Asigna o migra una conversación existente al usuario que inició sesión (adopción de chat).
     */
    @Transactional
    public void assignConversationToUser(String conversationId, String userId) {
        if (conversationId == null || userId == null || userId.isBlank()) return;
        conversationRepository.findById(conversationId).ifPresent(entity -> {
            entity.setUserId(userId);
            conversationRepository.save(entity);
        });
    }

    /**
     * Verifica si la conversación existe; si no, la crea con el primer mensaje como título.
     */
    private void ensureConversationExists(String conversationId, String firstMessage) {
        ensureConversationExists(conversationId, firstMessage, null);
    }

    private void ensureConversationExists(String conversationId, String firstMessage, String userId) {
        if (!conversationRepository.existsById(conversationId)) {
            String title = firstMessage.length() > 50
                    ? firstMessage.substring(0, 50) + "..."
                    : firstMessage;
            createConversation(conversationId, title, userId);
        }
    }
}
