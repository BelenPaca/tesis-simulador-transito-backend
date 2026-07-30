package com.programacion.taller3.services;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TransformerProcessor {

    public List<Document> procesar(List<Document> documents) {

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .build();

        List<Document> splitted = splitter.split(documents);

        System.out.println(" TraformerProcessor::documentos  creados: " + splitted.size());
        return splitted;


    }

}
