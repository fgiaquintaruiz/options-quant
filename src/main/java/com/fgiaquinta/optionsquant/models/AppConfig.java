package com.fgiaquinta.optionsquant.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    public IbkrConfig ibkr;
    public RiskConfig risk;
    public TelegramConfig telegram;
    public AiConfig ai; // Added so the bot can read the Gemini API Key

    // We use Object to capture any type of value
    public Map<String, Map<String, Object>> strategies;

    public static class IbkrConfig {
        public String host;
        public int port;
        public boolean autoExecute;
        public boolean postOnly;
        public List<String> tickers;
        public String accountId;
        public int syncTimeout = 30;// <-- ADDED THIS TO FIX THE ERROR
    }

    public static class TelegramConfig {
        public String botToken;
        public String chatId;
        public String masterKey;
    }

    public static class AiConfig {
        public String geminiApiKey;
        public boolean enabled;
    }

    public static class RiskConfig {
        public double riskPerTradePct;
        public int maxConcurrentTrades;
    }

    public double getParam(String category, String key) {
        if (strategies == null || !strategies.containsKey(category)) {
            throw new RuntimeException("❌ Category '" + category + "' not found in config.yaml");
        }

        Object value = strategies.get(category).get(key);
        if (value == null) {
            throw new RuntimeException("❌ Parameter '" + key + "' not found in " + category);
        }

        // Safe conversion
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        throw new RuntimeException("❌ Parameter '" + key + "' in " + category + " is not a number. Check YAML indentation.");
    }
}