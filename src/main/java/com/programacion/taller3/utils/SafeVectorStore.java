package com.programacion.taller3.utils;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SafeVectorStore implements VectorStore {

    private final VectorStore delegate;

    public SafeVectorStore(VectorStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public void add(List<Document> documents) {
        List<Document> safeDocuments = documents.stream().map(doc -> {
            Map<String, Object> safeMetadata = new HashMap<>();
            
            for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                Object value = entry.getValue();
                if (value == null) continue;
                
                // Qdrant soporta Strings, Números, Booleanos.
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    safeMetadata.put(entry.getKey(), value);
                } else {
                    // Si es un tipo complejo (como FinishReason$Value), lo convertimos a String
                    safeMetadata.put(entry.getKey(), String.valueOf(value));
                }
            }
            
            return new Document(doc.getId(), doc.getText(), safeMetadata);
        }).toList();

        this.delegate.add(safeDocuments);
    }

    @Override
    public void delete(List<String> idList) {
        this.delegate.delete(idList);
    }

    @Override
    public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
        this.delegate.delete(filterExpression);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return this.delegate.similaritySearch(request);
    }

    @Override
    public List<Document> similaritySearch(String query) {
        return this.delegate.similaritySearch(query);
    }
}
