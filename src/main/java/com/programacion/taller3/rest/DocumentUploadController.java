package com.programacion.taller3.rest;

import com.programacion.taller3.services.EmbeddingProcessor;
import com.programacion.taller3.services.FileProcessor;
import com.programacion.taller3.services.TransformerProcessor;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentUploadController {

    private final FileProcessor fileProcessor;
    private final TransformerProcessor transformerProcessor;
    private final EmbeddingProcessor embeddingProcessor;

    public DocumentUploadController(FileProcessor fileProcessor,
                                      TransformerProcessor transformerProcessor,
                                      EmbeddingProcessor embeddingProcessor) {
        this.fileProcessor = fileProcessor;
        this.transformerProcessor = transformerProcessor;
        this.embeddingProcessor = embeddingProcessor;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile multipartFile,
            @RequestHeader(value = "X-User-Id", required = false) String headerUserId,
            @RequestParam(value = "userId", required = false) String paramUserId) {
        Map<String, Object> response = new HashMap<>();

        if (multipartFile.isEmpty()) {
            response.put("status", "error");
            response.put("message", "El archivo proporcionado está vacío.");
            return ResponseEntity.badRequest().body(response);
        }

        File tempFile = null;
        try {
            String originalFilename = multipartFile.getOriginalFilename();
            String prefix = "upload_";
            String suffix = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".tmp";

            tempFile = File.createTempFile(prefix, suffix);

            try (InputStream in = multipartFile.getInputStream();
                 OutputStream out = new FileOutputStream(tempFile)) {
                in.transferTo(out);
            }

            String effectiveUserId = (paramUserId != null && !paramUserId.isBlank())
                    ? paramUserId
                    : (headerUserId != null && !headerUserId.isBlank() ? headerUserId : null);

            // 1. Leer con TikaDocumentReader y etiquetar categoría y usuario
            List<Document> rawDocs = fileProcessor.procesar(tempFile, originalFilename, effectiveUserId);

            // 2. Dividir en chunks
            List<Document> splitDocs = transformerProcessor.procesar(rawDocs);

            // 3. Generar embeddings y guardar en Qdrant VectorStore
            embeddingProcessor.procesar(splitDocs);

            response.put("status", "success");
            response.put("filename", originalFilename);
            response.put("chunksCount", splitDocs.size());
            response.put("message", "Documento '" + originalFilename + "' procesado e indexado en Qdrant exitosamente.");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            e.printStackTrace();
            response.put("status", "error");
            response.put("message", "Error al procesar el documento: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } finally {
            if (tempFile != null && tempFile.exists()) {
                try {
                    Files.delete(tempFile.toPath());
                } catch (Exception ignored) {
                }
            }
        }
    }
}
