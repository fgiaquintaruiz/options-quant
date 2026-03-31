package com.fgiaquinta.optionsquant.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgiaquinta.optionsquant.models.AnalysisResult;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AiNewsInterpreter {
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    public AnalysisResult analyzeHeadline(String headline) {
        String apiKey = ConfigLoader.getConfig().ai.geminiApiKey;
        if (apiKey == null || apiKey.isEmpty()) return null;

        String prompt = "Act as a quantitative analyst. Analyze this news headline: '" + headline + "'. " +
                "1. Classify sentiment (BULLISH, BEARISH, NEUTRAL). " +
                "2. Identify the primary industry affected. " +
                "3. Provide the top 3 relevant US tickers. " +
                "Respond ONLY with valid JSON: {\"bias\": \"BULLISH\", \"industry\": \"Semiconductors\", \"tickers\": [\"NVDA\", \"AMD\", \"TSM\"]}";

        try {
            String jsonBody = "{\"contents\": [{\"parts\":[{\"text\": \"" + prompt.replace("\"", "\\\"") + "\"}]}]}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode rootNode = mapper.readTree(response.body());
            String aiText = rootNode.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();
            aiText = aiText.replaceAll("```json", "").replaceAll("```", "").trim();

            return mapper.readValue(aiText, AnalysisResult.class);

        } catch (Exception e) {
            System.err.println("AI Error: " + e.getMessage());
            return null;
        }
    }
}