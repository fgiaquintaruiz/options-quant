package com.fgiaquinta.optionsquant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Optional {@code data/ticker-runtime.json} — universe, HOT order, and fundamental overrides (ABM).
 */
@Slf4j
@Component
public class TickerRuntimeConfigStore {

    static final Path RUNTIME_PATH = Path.of("data/ticker-runtime.json");

    /** Local mapper — Spring may not expose {@code ObjectMapper} as a bean in all setups. */
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public Optional<TickerRuntimeConfigPayload> load() {
        if (!Files.exists(RUNTIME_PATH)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(RUNTIME_PATH);
            if (bytes.length == 0) return Optional.empty();
            TickerRuntimeConfigPayload p = objectMapper.readValue(bytes, TickerRuntimeConfigPayload.class);
            return Optional.of(p);
        } catch (IOException e) {
            log.warn("Could not read {}: {}", RUNTIME_PATH.toAbsolutePath(), e.getMessage());
            return Optional.empty();
        }
    }

    public void save(TickerRuntimeConfigPayload payload) throws IOException {
        Path dir = RUNTIME_PATH.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = Path.of(RUNTIME_PATH.toString() + ".tmp");
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(payload);
        Files.write(tmp, json);
        Files.move(tmp, RUNTIME_PATH, StandardCopyOption.REPLACE_EXISTING);
        log.info("Saved ticker runtime config to {}", RUNTIME_PATH.toAbsolutePath());
    }
}
