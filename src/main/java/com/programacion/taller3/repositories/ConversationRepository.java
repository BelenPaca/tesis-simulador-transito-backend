package com.programacion.taller3.repositories;

import com.programacion.taller3.entities.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
    List<ConversationEntity> findAllByOrderByCreatedAtDesc();
    List<ConversationEntity> findByUserIdOrderByCreatedAtDesc(String userId);
    void deleteByUserId(String userId);

    @Query("SELECT c FROM ConversationEntity c WHERE (c.active IS NULL OR c.active = true) ORDER BY c.createdAt DESC")
    List<ConversationEntity> findActiveConversations();

    @Query("SELECT c FROM ConversationEntity c WHERE c.userId = :userId AND (c.active IS NULL OR c.active = true) ORDER BY c.createdAt DESC")
    List<ConversationEntity> findActiveByUserId(@Param("userId") String userId);
}
