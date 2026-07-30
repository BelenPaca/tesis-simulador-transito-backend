package com.programacion.taller3.repositories;

import com.programacion.taller3.entities.ConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {
    List<ConversationEntity> findAllByOrderByCreatedAtDesc();
}
