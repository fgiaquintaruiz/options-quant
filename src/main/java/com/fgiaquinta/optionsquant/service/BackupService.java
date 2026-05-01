package com.fgiaquinta.optionsquant.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private static final Path BACKUP_FILE = Path.of("data/settings-backup.json");

    public void saveSettings(String jsonBody) {
        Path tmp = null;
        try {
            Path dir = BACKUP_FILE.getParent();
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "settings-backup", ".tmp");
            Files.writeString(tmp, jsonBody);
            Files.move(tmp, BACKUP_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("Settings backup written to {}", BACKUP_FILE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write settings backup", e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }
}
