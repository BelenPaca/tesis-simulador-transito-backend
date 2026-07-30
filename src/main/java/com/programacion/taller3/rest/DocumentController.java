package com.programacion.taller3.rest;

import com.programacion.taller3.services.DocumentIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;

    public DocumentController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Endpoint para cargar archivos PDF e ingestarlos en Qdrant Vector Store
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocuments(
            @RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Debes enviar al menos un archivo PDF"));
        }

        Map<String, Object> result = ingestionService.ingestFiles(files);
        return ResponseEntity.ok(result);
    }
}
