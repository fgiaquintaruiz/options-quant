package com.fgiaquinta.optionsquant.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fgiaquinta.optionsquant.models.AppConfig;
import java.io.File;

public class ConfigLoader {
    private static AppConfig instance;

    public static AppConfig getConfig() {
        if (instance == null) {
            try {
                // Usamos YAMLFactory en lugar de la detección automática
                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                instance = mapper.readValue(new File("config.yaml"), AppConfig.class);
            } catch (Exception e) {
                throw new RuntimeException("❌ No se pudo cargar config.yaml: " + e.getMessage());
            }
        }
        return instance;
    }
}