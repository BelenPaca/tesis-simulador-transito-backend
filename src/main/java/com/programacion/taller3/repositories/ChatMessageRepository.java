package com.programacion.taller3.repositories;

import com.programacion.taller3.entities.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {
    List<ChatMessageEntity> findByConversationIdOrderByTimestampAsc(String conversationId);
    
    @Transactional
    void deleteByConversationId(String conversationId);
}
