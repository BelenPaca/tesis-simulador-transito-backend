package com.programacion.taller3.services;


import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmbeddingProcessor {

    @Autowired
    VectorStore vectorStore;

    @Autowired
    EmbeddingModel embeddingModel;

    public void procesar (List<Document> documents){
        System.out.println(" EmbeddingPRocesar procesar documentos: " + documents.size());

        System.out.println(embeddingModel);
        System.out.println(vectorStore);

        System.out.println("Enviando " + documents.size() + " documentos a Qdrant...");
        try {
            vectorStore.add(documents);
            System.out.println("Documentos guardados correctamente.");
        } catch (Exception e) {
            System.err.println("Error procesando documentos: " + e.getMessage());
            throw new RuntimeException("Error en Embedding API: " + e.getMessage(), e);
        }
    }
}
