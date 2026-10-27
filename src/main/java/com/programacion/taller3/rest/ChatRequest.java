package com.programacion.taller3.rest;

public record ChatRequest(String message, String conversationId, String userId, String attachedFileName) {
    public ChatRequest(String message, String conversationId, String userId) {
        this(message, conversationId, userId, null);
    }

    public ChatRequest(String message, String conversationId) {
        this(message, conversationId, null, null);
    }
}
