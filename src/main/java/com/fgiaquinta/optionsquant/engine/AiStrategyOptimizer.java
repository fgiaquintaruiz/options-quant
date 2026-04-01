package com.fgiaquinta.optionsquant.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgiaquinta.optionsquant.models.OptimizationResult;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AiStrategyOptimizer {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private static final int MAX_RETRIES = 3;

    public AiStrategyOptimizer() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.mapper = new ObjectMapper();
    }

    public List<OptimizationResult> analyzeBatch(Map<String, String> batchMetrics) {
        System.out.println("🧠 [AI Optimizer] Consolidating " + batchMetrics.size() + " strategies into a single batch request...");

        String prompt = "You are a quantitative researcher. Analyze the following backtest results for different strategies (provided in a JSON map where key=StrategyName and value=Metrics). " +
                "Optimize the TP/SL ATR multipliers for EACH strategy to improve the Win Rate and Profit Factor. " +
                "Return ONLY a JSON array of objects matching the OptimizationResult schema. \n\n" +
                "Data: " + batchMetrics.toString();

        return sendRequestWithBackoff(prompt);
    }

    private List<OptimizationResult> sendRequestWithBackoff(String prompt) {
        int attempt = 0;
        while (attempt < MAX_RETRIES) {
            try {
                String apiKey = String.valueOf(ConfigLoader.getConfig().getParam("global", "geminiApiKey"));
                String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

                String jsonBody = "{ \"contents\": [{\"parts\":[{\"text\": \"" + prompt.replace("\"", "\\\"").replace("\n", "\\n") + "\"}]}] }";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    String rawResponse = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();

                    String cleanJson = rawResponse.replaceAll("(?s)```json\\s*(.*?)\\s*```", "$1").replaceAll("```", "").trim();
                    return mapper.readValue(cleanJson, new TypeReference<>() {
                    });
                }

                if (response.statusCode() == 429) {
                    attempt++;
                    long waitTime = extractRetryDelay(response.body(), attempt);
                    System.err.println("⚠️ [AI Optimizer] Quota exceeded (429). Retrying in " + waitTime + "ms... (Attempt " + attempt + "/" + MAX_RETRIES + ")");
                    Thread.sleep(waitTime);
                } else {
                    System.err.println("❌ [AI Optimizer] API Error: " + response.statusCode() + " - " + response.body());
                    break;
                }
            } catch (Exception e) {
                System.err.println("❌ [AI Optimizer] Exception: " + e.getMessage());
                break;
            }
        }
        return new ArrayList<>();
    }

    private long extractRetryDelay(String responseBody, int attempt) {
        try {
            JsonNode errorRoot = mapper.readTree(responseBody);
            JsonNode details = errorRoot.path("error").path("details");

            // 1. Try to find the official retryDelay in the JSON response
            for (JsonNode detail : details) {
                if (detail.has("retryDelay")) {
                    String delayStr = detail.path("retryDelay").asText(); // e.g., "27.199s"
                    if (delayStr.endsWith("s")) {
                        // Remove the 's', parse as double to handle decimals, and convert to ms
                        double seconds = Double.parseDouble(delayStr.substring(0, delayStr.length() - 1));
                        return (long) (seconds * 1000) + 500; // Add 500ms safety buffer
                    }
                }
            }
        } catch (Exception e) {
            // If parsing fails, proceed to manual fallback
        }

        // 2. Fallback: Exponential Backoff manual (3s, 9s, 27s...)
        // Calculation: 3^attempt * 1000 milliseconds
        return (long) Math.pow(3, attempt) * 1000;
    }


}