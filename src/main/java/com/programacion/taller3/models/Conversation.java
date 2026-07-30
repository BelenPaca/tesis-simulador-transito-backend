package com.programacion.taller3.models;

import java.util.List;

public record Conversation(
    String id,
    String title,
    String createdAt,
    List<ChatMessage> messages
) {}
