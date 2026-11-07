package com.programacion.taller3.services;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio de Jerarquía Legal y Enriquecimiento de Fragmentos Huérfanos.
 * Carga en memoria al iniciar la aplicación los documentos legales desde classpath*:normativa/*.md
 * usando PathMatchingResourcePatternResolver y UTF-8 para compatibilidad absoluta entre Windows y Linux (Render).
 * Conecta fragmentos que son numerales huérfanos con el encabezado sancionatorio de su artículo padre
 * de manera 100% genérica (sin datos quemados).
 */
@Service
public class LegalHierarchyService {

    private static final Logger log = LoggerFactory.getLogger(LegalHierarchyService.class);

    public record ArticuloLegal(
            String sourceFile,
            String articleTitle,
            String headerAndSanction,
            String rawFullText,
            String normalizedText
    ) {}

    // Mapa: nombreArchivoNormalizado -> Lista de artículos
    private final Map<String, List<ArticuloLegal>> articulosPorArchivo = new HashMap<>();
    private final List<ArticuloLegal> todosLosArticulos = new ArrayList<>();

    @PostConstruct
    public void init() {
        log.info("Inicializando LegalHierarchyService: cargando corpus normativo desde classpath*:normativa/*.md...");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

        try {
            Resource[] resources = resolver.getResources("classpath*:normativa/*.md");
            log.info("Total archivos normativos detectados en resources: {}", resources.length);

            for (Resource res : resources) {
                String filename = res.getFilename();
                if (filename == null || filename.equalsIgnoreCase("README.md")) {
                    continue;
                }

                String content;
                try (Reader reader = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                    StringBuilder sb = new StringBuilder();
                    char[] buffer = new char[4096];
                    int read;
                    while ((read = reader.read(buffer, 0, buffer.length)) != -1) {
                        sb.append(buffer, 0, read);
                    }
                    content = sb.toString();
                }

                List<ArticuloLegal> articulos = parsearArticulos(filename, content);
                String normalizedFileName = normalizeFileName(filename);
                articulosPorArchivo.put(normalizedFileName, articulos);
                todosLosArticulos.addAll(articulos);

                log.info("Archivo [{}] cargado: {} artículos indexados en memoria", filename, articulos.size());
            }

            log.info("LegalHierarchyService inicializado exitosamente: {} artículos totales en memoria", todosLosArticulos.size());

        } catch (Exception e) {
            log.error("Error al cargar corpus normativo en LegalHierarchyService: {}", e.getMessage(), e);
        }
    }

    private List<ArticuloLegal> parsearArticulos(String sourceFile, String content) {
        List<ArticuloLegal> list = new ArrayList<>();
        if (content == null || content.isBlank()) return list;

        // Dividir por encabezados de artículos (### Art. o similar)
        Pattern articlePattern = Pattern.compile("(?m)^(?=#{1,4}\\s*Art(?:ículo|\\.)?\\b)", Pattern.CASE_INSENSITIVE);
        String[] sections = articlePattern.split(content);

        for (String sec : sections) {
            String trimmed = sec.trim();
            if (trimmed.length() < 20) continue;

            // Extraer título del artículo (primera línea o primera frase)
            String[] lines = trimmed.split("\n", 2);
            String titleLine = lines[0].trim();

            // Extraer encabezado introductorio sancionatorio (hasta antes del primer numeral o los primeros 400 caracteres)
            String header = extractHeaderAndSanction(trimmed);

            String normalizedFull = normalizeText(trimmed);

            list.add(new ArticuloLegal(
                    sourceFile,
                    titleLine,
                    header,
                    trimmed,
                    normalizedFull
            ));
        }

        return list;
    }

    private String extractHeaderAndSanction(String articleText) {
        if (articleText == null || articleText.isBlank()) return "";

        // Caso general: Buscar hasta antes del primer numeral con formato: "1. ", "1.- "
        Matcher matcher = Pattern.compile("(?m)^\\s*1[\\.\\-]\\s+", Pattern.CASE_INSENSITIVE).matcher(articleText);
        if (matcher.find()) {
            return articleText.substring(0, matcher.start()).trim();
        }

        // Si no hay lista numerada explícita, tomar el primer párrafo completo
        String[] paragraphs = articleText.split("\\n\\s*\\n", 2);
        if (paragraphs.length > 0) {
            return paragraphs[0].trim();
        }

        return articleText.length() > 300 ? articleText.substring(0, 300).trim() : articleText.trim();
    }

    /**
     * Enriquecimiento genérico de fragmentos: si un chunk no tiene su encabezado padre,
     * busca en memoria a qué artículo pertenece y le antepone el encabezado sancionatorio padre.
     */
    public String enrichChunkIfNeeded(String chunkText, String sourceFileName) {
        if (chunkText == null || chunkText.isBlank()) {
            return chunkText;
        }

        String trimmed = chunkText.trim();

        // Si el fragmento ya empieza con su encabezado de artículo, no necesita enriquecimiento
        if (trimmed.startsWith("### Art") || trimmed.startsWith("## Art") || trimmed.startsWith("# Art")) {
            return chunkText;
        }

        // Buscar en los artículos del archivo origen o en toda la base
        List<ArticuloLegal> candidatos = Collections.emptyList();
        if (sourceFileName != null && !sourceFileName.isBlank()) {
            String normSource = normalizeFileName(sourceFileName);
            candidatos = articulosPorArchivo.getOrDefault(normSource, Collections.emptyList());
            if (candidatos.isEmpty()) {
                // Probar coincidencia parcial de nombre de archivo
                for (Map.Entry<String, List<ArticuloLegal>> entry : articulosPorArchivo.entrySet()) {
                    if (entry.getKey().contains(normSource) || normSource.contains(entry.getKey())) {
                        candidatos = entry.getValue();
                        break;
                    }
                }
            }
        }

        if (candidatos.isEmpty()) {
            candidatos = todosLosArticulos;
        }

        ArticuloLegal padre = null;

        // Estrategia 1: Muestra inicial (primeros 100 caracteres normalizados)
        String sample1 = extractSearchSample(trimmed, 0, 100);
        if (sample1.length() >= 15) {
            for (ArticuloLegal art : candidatos) {
                if (art.normalizedText().contains(sample1)) {
                    padre = art;
                    break;
                }
            }
        }

        // Estrategia 2: Si no hubo match, buscar por la primera línea significativa (útil cuando empieza con "6. La o el conductor...")
        if (padre == null) {
            String[] lines = trimmed.split("\n");
            for (String line : lines) {
                String lineNorm = normalizeText(line);
                if (lineNorm.length() >= 20) {
                    String subSample = lineNorm.length() > 80 ? lineNorm.substring(0, 80) : lineNorm;
                    for (ArticuloLegal art : candidatos) {
                        if (art.normalizedText().contains(subSample)) {
                            padre = art;
                            break;
                        }
                    }
                    if (padre != null) break;
                }
            }
        }

        // Si encontramos el artículo padre, anteponer el encabezado padre con el nombre de la ley
        if (padre != null) {
            StringBuilder enriched = new StringBuilder();
            enriched.append("[MARCO LEGAL OFICIAL: ").append(padre.sourceFile()).append(" - ").append(padre.articleTitle()).append("]\n");
            enriched.append(padre.headerAndSanction()).append("\n\n");
            enriched.append(trimmed);
            return enriched.toString();
        }

        return chunkText;
    }

    private String extractSearchSample(String text, int start, int maxLen) {
        String normalized = normalizeText(text);
        if (normalized.length() <= start) return "";
        int end = Math.min(normalized.length(), start + maxLen);
        return normalized.substring(start, end).trim();
    }

    private String normalizeText(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[^a-záéíóúüñ0-9 ]", "")
                .trim();
    }

    private String normalizeFileName(String filename) {
        if (filename == null) return "";
        String clean = filename;
        int lastSlash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            clean = clean.substring(lastSlash + 1);
        }
        return clean.toLowerCase().trim();
    }
}
