package com.programacion.taller3.models;

import java.util.List;

public record Conversation(
    String id,
    String title,
    String createdAt,
    List<ChatMessage> messages,
    String userId
) {
    public Conversation(String id, String title, String createdAt, List<ChatMessage> messages) {
        this(id, title, createdAt, messages, null);
    }
}
