package com.programacion.taller3.services;

import org.apache.camel.Handler;
import org.apache.camel.Header;
import org.springframework.ai.document.Document;
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
        return procesar(file, file != null ? file.getName() : "documento.pdf");
    }

    @Handler
    public List<Document> procesar(File file, @Header("CamelFileName") String originalFilename) {
        Resource resource = new FileSystemResource(file);
        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        String finalName = (originalFilename != null && !originalFilename.isBlank())
                ? originalFilename
                : (file != null ? file.getName() : "documento.pdf");

        List<Document> documentosConMetadata = documents.stream().map(doc -> {
            Map<String, Object> nuevosMetadatos = new HashMap<>(doc.getMetadata());
            nuevosMetadatos.put("categoria", "documento");
            nuevosMetadatos.put("source", finalName);
            nuevosMetadatos.put("file_name", finalName);
            nuevosMetadatos.put("original_filename", finalName);
            return new Document(doc.getId(), doc.getText(), nuevosMetadatos);
        }).toList();

        System.out.println("Documentos creados para '" + finalName + "': " + documentosConMetadata.size());
        return documentosConMetadata;
    }
}
