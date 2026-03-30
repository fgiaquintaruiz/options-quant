package com.fgiaquinta.optionsquant.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    public IbkrConfig ibkr;
    public TelegramConfig telegram;

    // Usamos Object para capturar cualquier tipo de valor (número o mapa erróneo)
    public Map<String, Map<String, Object>> strategies;

    public static class IbkrConfig {
        public String host;
        public int port;
        public boolean autoExecute;
        public List<String> tickers;
    }

    public static class TelegramConfig {
        public String botToken;
        public String chatId;
        public String masterKey;
    }

    public double getParam(String category, String key) {
        if (strategies == null || !strategies.containsKey(category)) {
            throw new RuntimeException("❌ Categoría '" + category + "' no encontrada en config.yaml");
        }

        Object value = strategies.get(category).get(key);
        if (value == null) {
            throw new RuntimeException("❌ Parámetro '" + key + "' no encontrado en " + category);
        }

        // Conversión segura: acepta tanto 10 como 10.0
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        throw new RuntimeException("❌ El parámetro '" + key + "' en " + category + " no es un número. Revisa la indentación del YAML.");
    }
}