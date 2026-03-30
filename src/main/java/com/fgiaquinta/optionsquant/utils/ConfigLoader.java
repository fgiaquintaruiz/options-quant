package com.fgiaquinta.optionsquant.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fgiaquinta.optionsquant.models.AppConfig;
import java.io.InputStream;

public class ConfigLoader {
    private static AppConfig instance;

    public static AppConfig getConfig() {
        if (instance == null) {
            try (InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream("config.yaml")) {
                if (is == null) {
                    throw new RuntimeException("config.yaml not found in src/main/resources");
                }

                ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
                instance = mapper.readValue(is, AppConfig.class);

                System.out.println("⚙️ Configuration loaded successfully from resources.");

            } catch (Exception e) {
                System.err.println("🚨 FATAL ERROR: Could not read config.yaml from resources");
                e.printStackTrace();
                System.exit(1);
            }
        }
        return instance;
    }
}