package com.fgiaquinta.optionsquant.config;

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
@RestController
public class ChartController {

    private static final Path CHARTS_DIR = Paths.get("backtest/charts").toAbsolutePath();

    @GetMapping("/charts/{filename}")
    public ResponseEntity<Resource> getChart(@PathVariable String filename) throws IOException {
        Path filePath = CHARTS_DIR.resolve(filename).normalize();
        
        // Security: prevent directory traversal
        if (!filePath.startsWith(CHARTS_DIR)) {
            return ResponseEntity.notFound().build();
        }
        
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build();
        }
        
        Resource resource = new FileSystemResource(filePath);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }
}
