package com.fgiaquinta.optionsquant.models;

import java.util.List;
import java.util.Map;

public class AppConfig {
    public IbkrConfig ibkr;
    public TelegramConfig telegram;
    public Map<String, Map<String, Double>> strategies;

    public static class IbkrConfig {
        public String host;
        public int port;
        public List<String> tickers;
    }

    public static class TelegramConfig {
        public String botToken;
        public String chatId;
        public String masterKey;
    }

    /**
     * Helper para obtener parámetros de estrategias de forma rápida.
     * Ejemplo: getParam("continuation", "tp")
     */
    public double getParam(String category, String key) {
        if (strategies == null || !strategies.containsKey(category)) {
            throw new RuntimeException("Categoría de estrategia no encontrada en config.yaml: " + category);
        }
        Map<String, Double> categoryParams = strategies.get(category);
        if (!categoryParams.containsKey(key)) {
            throw new RuntimeException("Parámetro '" + key + "' no encontrado en la categoría: " + category);
        }
        return categoryParams.get(key);
    }
}