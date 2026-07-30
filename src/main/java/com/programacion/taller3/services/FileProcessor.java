package com.programacion.taller3.services;

import org.apache.catalina.webresources.FileResource;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class FileProcessor {

    public List<Document> procesar(File file) {
        Resource resource = new FileSystemResource(file);
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        List<Document> documentosConMetadata = documents.stream().map(doc -> {
            Map<String, Object> nuevosMetadatos = new HashMap<>(doc.getMetadata());
            nuevosMetadatos.put("categoria", "documento");
            return new Document(doc.getId(), doc.getText(), nuevosMetadatos);
        }).toList();

        System.out.println("Documentos creados: " + documentosConMetadata.size());
        return documentosConMetadata;

    }

}
