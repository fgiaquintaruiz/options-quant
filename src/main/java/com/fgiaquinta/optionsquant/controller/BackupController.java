package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.service.BackupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API for settings backup.
 */
@Slf4j
@RestController
@RequestMapping("/api/backup")
@RequiredArgsConstructor
public class BackupController {

    private final BackupService backupService;

    @PostMapping
    public ResponseEntity<?> saveBackup(@RequestBody String body) {
        log.info(">>> POST /api/backup - saving settings snapshot");
        backupService.saveSettings(body);
        log.info("<<< POST /api/backup - done");
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
