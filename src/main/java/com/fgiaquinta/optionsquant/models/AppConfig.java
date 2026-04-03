package com.fgiaquinta.optionsquant.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    // Campos de configuración de primer nivel
    public IbkrConfig ibkr;
    public RiskConfig risk;
    public TelegramConfig telegram;
    public AiConfig ai;

    // Mapa dinámico para las estrategias (global, opening, etc.)
    public Map<String, Map<String, Object>> strategies;

    public static class IbkrConfig {
        public String host;
        public int port;
        public boolean autoExecute;
        public java.util.List<String> tickers;
        public String accountId;
        public int syncTimeout = 30;
    }

    public static class TelegramConfig {
        public String botToken;
        public String chatId;
        public String masterKey;
    }

    public static class AiConfig {
        public String geminiApiKey;
        public boolean enabled;
        public String endpointBase;
    }

    public static class RiskConfig {
        public double riskPerTradePct;
        public int maxConcurrentTrades;
    }

    /**
     * Método universal para obtener parámetros numéricos (double).
     * Busca primero en 'strategies' y luego intenta en campos de primer nivel.
     */
    public double getDouble(String category, String key) {
        Object value = getRawValue(category, key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new RuntimeException("❌ El parámetro '" + key + "' en '" + category + "' no es un número.");
    }

    /**
     * Método universal para obtener parámetros de texto (String).
     */
    public String getString(String category, String key) {
        Object value = getRawValue(category, key);
        return String.valueOf(value);
    }

    public boolean getBoolean(String category, String key) {
        Object value = getRawValue(category, key);

        // If Jackson correctly parsed it as a Boolean object from the YAML
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        // If it was parsed as a String (e.g., wrapped in quotes in the YAML)
        else if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }

        throw new RuntimeException("❌ Parameter '" + key + "' in '" + category + "' is missing or not a boolean.");
    }

    public List<String> getList(String section, String key) {
        String value = getString(section, key);
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // 👉 STRIP BRACKETS: Remove [ and ] if they exist in the string
        String cleanValue = value.replace("[", "").replace("]", "");

        return Arrays.stream(cleanValue.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Lógica central de búsqueda en el YAML.
     */
    private Object getRawValue(String category, String key) {
        // 1. Intentar buscar en el mapa dinámico de estrategias (global, opening, etc.)
        if (strategies != null && strategies.containsKey(category)) {
            Object val = strategies.get(category).get(key);
            if (val != null) return val;
        }

        // 2. Intentar buscar en los objetos de primer nivel (ai, ibkr, risk)
        if ("ai".equalsIgnoreCase(category)) {
            if ("geminiApiKey".equals(key)) return ai.geminiApiKey;
            if ("enabled".equals(key)) return ai.enabled;
            if ("endpointBase".equals(key)) return ai.endpointBase; // <-- Add this line
        }

        if ("risk".equalsIgnoreCase(category)) {
            if ("riskPerTradePct".equals(key)) return risk.riskPerTradePct;
            if ("maxConcurrentTrades".equals(key)) return risk.maxConcurrentTrades;
        }

        if ("telegram".equalsIgnoreCase(category)) {
            if ("botToken".equals(key)) return telegram.botToken;
            if ("chatId".equals(key)) return telegram.chatId;
            if ("masterKey".equals(key)) return telegram.masterKey;
        }

        if ("ibkr".equalsIgnoreCase(category)) {
            if ("host".equals(key)) return ibkr.host;
            if ("port".equals(key)) return ibkr.port;
            if ("accountId".equals(key)) return ibkr.accountId;
            if ("syncTimeout".equals(key)) return ibkr.syncTimeout;
            if ("autoExecute".equals(key)) return ibkr.autoExecute;
            if ("tickers".equals(key)) return ibkr.tickers;
        }

        throw new RuntimeException("❌ No se encontró la categoría '" + category + "' o la clave '" + key + "' en config.yaml");
    }
}