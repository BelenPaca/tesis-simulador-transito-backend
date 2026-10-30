package com.programacion.taller3.services;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TransformerProcessor {

    public List<Document> procesar(List<Document> documents) {

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .build();

        List<Document> splitted = splitter.split(documents);

        // Garantizar que cada chunk conserve los metadatos del documento padre.
        // TokenTextSplitter a veces no propaga todos los campos de metadata,
        // lo que hace que los filtros de Qdrant (source, file_name, tipo, categoria)
        // no encuentren nada al buscar. Aqui los restauramos explicitamente.
        List<Document> withMetadata = new ArrayList<>(splitted.size());
        for (Document chunk : splitted) {
            Document parent = findParent(documents, chunk);
            Map<String, Object> merged = new HashMap<>();
            if (parent != null) {
                merged.putAll(parent.getMetadata());
            }
            merged.putAll(chunk.getMetadata()); // el chunk puede tener valores que prevalecen
            withMetadata.add(new Document(chunk.getId(), chunk.getText(), merged));
        }

        System.out.println("TransformerProcessor: chunks con metadatos completos: " + withMetadata.size());
        return withMetadata;
    }

    private Document findParent(List<Document> parents, Document chunk) {
        String chunkText = chunk.getText();
        if (chunkText == null || chunkText.isBlank()) {
            return parents.isEmpty() ? null : parents.get(0);
        }
        String probe = chunkText.length() > 80 ? chunkText.substring(0, 80) : chunkText;
        for (Document p : parents) {
            if (p.getText() != null && p.getText().contains(probe)) {
                return p;
            }
        }
        return parents.isEmpty() ? null : parents.get(0);
    }
}
