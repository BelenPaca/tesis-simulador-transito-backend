package com.programacion.taller3.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "conversations")
public class ConversationEntity {

    @Id
    private String id;
    private String title;
    private LocalDateTime createdAt;
    private String userId;
    private Boolean active = true;

    // Columna nullable para almacenar nombres de archivos adjuntos acumulados separados por comas
    // ej: "informe_pericial.pdf,guia_infracciones.pdf"
    @Column(name = "attached_files", length = 1000, nullable = true)
    private String attachedFiles;

    public ConversationEntity() {
        this.active = true;
    }

    public ConversationEntity(String id, String title, LocalDateTime createdAt) {
        this(id, title, createdAt, null);
    }

    public ConversationEntity(String id, String title, LocalDateTime createdAt, String userId) {
        this.id = id;
        this.title = title;
        this.createdAt = createdAt;
        this.userId = userId;
        this.active = true;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Boolean getActive() {
        return active != null ? active : true;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String getAttachedFiles() {
        return attachedFiles;
    }

    public void setAttachedFiles(String attachedFiles) {
        this.attachedFiles = attachedFiles;
    }
}
