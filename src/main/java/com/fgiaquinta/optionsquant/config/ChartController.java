package com.fgiaquinta.optionsquant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Serves backtest chart HTML files directly.
 */
@Slf4j
@RestController
public class ChartController {

    private static final Path CHARTS_DIR = Paths.get("backtest/charts").toAbsolutePath();

    @GetMapping("/charts/{filename:.+}")
    public ResponseEntity<Resource> getChart(@PathVariable String filename) throws IOException {
        // Decode the filename (URL encoding might replace special chars)
        String decodedFilename = java.net.URLDecoder.decode(filename, java.nio.charset.StandardCharsets.UTF_8);

        // Security check: ensure filename doesn't contain path traversal
        if (decodedFilename.contains("..") || decodedFilename.contains("/") || decodedFilename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }

        Path filePath = CHARTS_DIR.resolve(decodedFilename).normalize();

        // Security: prevent directory traversal
        if (!filePath.startsWith(CHARTS_DIR)) {
            return ResponseEntity.notFound().build();
        }

        if (!Files.exists(filePath)) {
            // Try to find a matching file with slightly different time format
            // (e.g., 16-00-00 vs 16-00)
            Path matched = findMatchingChartFile(decodedFilename);
            if (matched != null) {
                filePath = matched;
            } else {
                log.warn("Chart file not found: {} (resolved to: {})", decodedFilename, filePath);
                return ResponseEntity.status(404).body(null);
            }
        }

        Resource resource = new FileSystemResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + decodedFilename + "\"")
                .body(resource);
    }

    /**
     * Tries to find a chart file with a slightly different time format.
     * Handles cases like "16-00-00" vs "16-00" in the timestamp portion.
     */
    private Path findMatchingChartFile(String requestedFilename) {
        try {
            if (!Files.exists(CHARTS_DIR) || !Files.isDirectory(CHARTS_DIR)) {
                return null;
            }

            // Extract the base name without extension for pattern matching
            String baseName = requestedFilename.endsWith(".html")
                    ? requestedFilename.substring(0, requestedFilename.length() - 5)
                    : requestedFilename;

            // Try removing potential extra time segment (e.g., "16-00-00" -> "16-00")
            // Pattern: TICKER_STRATEGY_DIR_YYYY-MM-DD_HH-MM-SS.html
            // Try to match TICKER_STRATEGY_DIR_YYYY-MM-DD_HH-MM.html
            String patternWithoutSeconds = baseName.replaceAll("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2})-\\d{2}$", "$1");

            if (!patternWithoutSeconds.equals(baseName)) {
                Path candidate = CHARTS_DIR.resolve(patternWithoutSeconds + ".html");
                if (Files.exists(candidate)) {
                    log.info("Found chart file with normalized time: {} -> {}", requestedFilename, candidate.getFileName());
                    return candidate;
                }
            }

            // Try adding extra time segment (e.g., "16-00" -> "16-00-00")
            String patternWithSeconds = baseName.replaceAll("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2})$", "$1-00");
            Path candidateWithSeconds = CHARTS_DIR.resolve(patternWithSeconds + ".html");
            if (Files.exists(candidateWithSeconds)) {
                log.info("Found chart file with extended time: {} -> {}", requestedFilename, candidateWithSeconds.getFileName());
                return candidateWithSeconds;
            }

            // Last resort: list files and find one that starts with the same base
            try (var stream = Files.list(CHARTS_DIR)) {
                return stream.filter(p -> {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".html")) return false;
                    String otherBase = name.substring(0, name.length() - 5);
                    // Match if the first N characters are the same (ticker_strategy_direction match)
                    String[] parts1 = baseName.split("_");
                    String[] parts2 = otherBase.split("_");
                    if (parts1.length >= 3 && parts2.length >= 3) {
                        return parts1[0].equals(parts2[0]) && parts1[1].equals(parts2[1]) && parts1[2].equals(parts2[2]);
                    }
                    return false;
                }).findFirst().orElse(null);
            }
        } catch (IOException e) {
            log.debug("Error while searching for matching chart file: {}", e.getMessage());
            return null;
        }
    }
}
