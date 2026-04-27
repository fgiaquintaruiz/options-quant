package com.fgiaquinta.optionsquant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final Path BACKUP_FILE = Path.of("data/settings-backup.json");

    public void saveSettings(String jsonBody) {
        try {
            Files.createDirectories(BACKUP_FILE.getParent());
            Files.writeString(BACKUP_FILE, jsonBody, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Settings backup written to {}", BACKUP_FILE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write settings backup", e);
        }
    }
}
