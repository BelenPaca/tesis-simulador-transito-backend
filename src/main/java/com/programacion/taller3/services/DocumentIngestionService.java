package com.programacion.taller3.services;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentIngestionService {

    private final FileProcessor fileProcessor;
    private final TransformerProcessor transformerProcessor;
    private final EmbeddingProcessor embeddingProcessor;

    public DocumentIngestionService(FileProcessor fileProcessor,
                                     TransformerProcessor transformerProcessor,
                                     EmbeddingProcessor embeddingProcessor) {
        this.fileProcessor = fileProcessor;
        this.transformerProcessor = transformerProcessor;
        this.embeddingProcessor = embeddingProcessor;
    }

    public Map<String, Object> ingestFiles(List<MultipartFile> files) {
        int totalDocuments = 0;
        List<String> processedFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file.isEmpty()) continue;
            try {
                String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "documento.pdf";
                File tempFile = File.createTempFile("upload-", "-" + originalFilename);
                file.transferTo(tempFile);

                List<Document> documents = fileProcessor.procesar(tempFile);
                List<Document> chunks = transformerProcessor.procesar(documents);
                embeddingProcessor.procesar(chunks);

                totalDocuments += chunks.size();
                processedFiles.add(originalFilename);

                tempFile.delete();
            } catch (Exception e) {
                String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "desconocido";
                errors.add(originalFilename + ": " + e.getMessage());
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("processedFiles", processedFiles);
        response.put("totalChunks", totalDocuments);
        response.put("errors", errors);

        return response;
    }
}
