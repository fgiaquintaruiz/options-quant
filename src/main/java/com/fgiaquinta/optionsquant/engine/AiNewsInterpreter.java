package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.utils.ConfigLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class AiNewsInterpreter {
    private final HttpClient httpClient;

    public AiNewsInterpreter() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // Real Implementation: Gemini Sentiment Analysis
    public boolean isSentimentFavorable(boolean isCall) {
        System.out.println("🧠 [AiNewsInterpreter] Consulting Gemini for real-time macro sentiment...");

        try {
            // Replace "geminiApiKey" with the exact key name you use in your config.yaml
            String apiKey = String.valueOf(ConfigLoader.getConfig().getParam("global", "geminiApiKey"));

            if (apiKey == null || apiKey.isEmpty()) {
                System.out.println("⚠️ [AiNewsInterpreter] No Gemini API key found in config. Bypassing AI check.");
                return true;
            }

            // Using Gemini 1.5 Flash for rapid, low-latency trading decisions
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + apiKey;

            // Prompt engineering for a strict quantitative response
            String prompt = "You are a quantitative trading AI. Based on the current macroeconomic news and market sentiment today, respond with exactly one word: BULLISH, BEARISH, or NEUTRAL.";
            String jsonBody = "{ \"contents\": [{\"parts\":[{\"text\": \"" + prompt + "\"}]}] }";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String responseBody = response.body().toUpperCase();

                // Extract the exact sentiment word from the JSON response
                boolean isBullish = responseBody.contains("BULLISH");
                boolean isBearish = responseBody.contains("BEARISH");

                System.out.print("🤖 [Gemini Verdict] ");
                if (isBullish) System.out.println("BULLISH 📈");
                else if (isBearish) System.out.println("BEARISH 📉");
                else System.out.println("NEUTRAL ⚖️");

                if (isCall) {
                    return !isBearish; // Allow CALLs if sentiment is BULLISH or NEUTRAL
                } else {
                    return !isBullish; // Allow PUTs if sentiment is BEARISH or NEUTRAL
                }
            } else {
                System.err.println("❌ [AiNewsInterpreter] Gemini API HTTP Error: " + response.statusCode());
            }

        } catch (Exception e) {
            System.err.println("❌ [AiNewsInterpreter] Exception calling Gemini: " + e.getMessage());
        }

        // Fallback to true so the trading engine doesn't halt if the API fails or times out
        return true;
    }
}