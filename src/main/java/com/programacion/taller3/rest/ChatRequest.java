package com.programacion.taller3.rest;

public record ChatRequest(String message, String conversationId, String userId) {
    public ChatRequest(String message, String conversationId) {
        this(message, conversationId, null);
    }
}
