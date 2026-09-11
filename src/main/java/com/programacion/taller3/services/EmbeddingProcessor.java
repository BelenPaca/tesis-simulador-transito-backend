package com.programacion.taller3.services;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingProcessor {

    @Autowired
    VectorStore vectorStore;

    @Autowired
    EmbeddingModel embeddingModel;

    public void procesar(List<Document> documents) {
        System.out.println("EmbeddingProcessor: total de fragmentos a procesar: " + documents.size());

        // Lotes de 50 fragmentos para procesar hasta 5x más rápido sin saturar la cuota
        int batchSize = 50;
        int total = documents.size();

        for (int i = 0; i < total; i += batchSize) {
            int end = Math.min(i + batchSize, total);
            List<Document> batch = new ArrayList<>(documents.subList(i, end));
            System.out.println("Enviando lote " + (i / batchSize + 1) + " (" + (i + 1) + " a " + end + " de " + total + ") a Qdrant...");

            boolean success = false;
            int retries = 0;

            while (!success && retries < 5) {
                try {
                    vectorStore.add(batch);
                    success = true;
                    // Pausa breve de 500ms entre lotes grandes
                    Thread.sleep(500);
                } catch (Exception e) {
                    retries++;
                    System.err.println("Reintentando lote (" + retries + "/5) por cuota/espera: " + e.getMessage());
                    try {
                        Thread.sleep(3000L * retries);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (retries >= 5) {
                        throw new RuntimeException("Error en Embedding API tras reintentos: " + e.getMessage(), e);
                    }
                }
            }
        }
        System.out.println("Todos los documentos se guardaron correctamente en Qdrant.");
    }
}
