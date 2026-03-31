package com.fgiaquinta.optionsquant.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgiaquinta.optionsquant.models.OptimizationResult;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AiStrategyOptimizer {
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public OptimizationResult analyzeBacktest(String backtestJsonMetrics) {
        String apiKey = ConfigLoader.getConfig().ai.geminiApiKey;
        if (apiKey == null || apiKey.isEmpty()) return null;

        String prompt = "You are a Quantitative Trading Director. Review the following recent backtest metrics for our algorithmic strategy: "
                + backtestJsonMetrics + " "
                + "Analyze the performance. Based on the win rate and drawdown, suggest adjustments to the 'tpAtrMultiplier' (Take Profit) and 'slAtrMultiplier' (Stop Loss). "
                + "If the drawdown is high, widen the Stop Loss. If the win rate is low, tighten the Take Profit. "
                + "Respond ONLY in a valid JSON format matching this structure exactly: "
                + "{\"ticker\": \"AAPL\", \"strategy\": \"P5_CONTINUATION\", \"insight\": \"High drawdown detected, widening SL.\", \"recommendedTpAtr\": 1.2, \"recommendedSlAtr\": 1.5}";

        try {
            String jsonBody = "{\"contents\": [{\"parts\":[{\"text\": \"" + prompt.replace("\"", "\\\"") + "\"}]}]}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_API_URL + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            String aiText = mapper.readTree(response.body())
                    .path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText()
                    .replaceAll("```json", "").replaceAll("```", "").trim();

            return mapper.readValue(aiText, OptimizationResult.class);

        } catch (Exception e) {
            System.err.println("Optimization analysis failed: " + e.getMessage());
            return null;
        }
    }
}