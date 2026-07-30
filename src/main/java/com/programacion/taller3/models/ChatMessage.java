package com.programacion.taller3.models;

public record ChatMessage(
    String role,
    String content,
    String timestamp
) {}
