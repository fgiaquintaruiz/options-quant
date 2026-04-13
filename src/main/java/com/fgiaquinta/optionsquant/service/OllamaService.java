package com.fgiaquinta.optionsquant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Local AI service using Ollama (qwen2.5:7b model).
 * Replaces cloud-based Gemini with private, local AI inference.
 * 
 * Features:
 * - Macro sentiment analysis (BULLISH/BEARISH/NEUTRAL)
 * - Strategy optimization recommendations
 * - No API costs, fully private
 */
@Slf4j
@Service
public class OllamaService {

    private static final String OLLAMA_URL = "http://localhost:11434";
    private static final String MODEL = "qwen2.5:7b";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    /**
     * Analyzes macroeconomic news and returns sentiment.
     * 
     * @param newsHeadlines News headlines to analyze
     * @return BULLISH, BEARISH, or NEUTRAL
     */
    public Sentiment analyzeSentiment(String newsHeadlines) {
        try {
            String prompt = """
                    You are a quantitative trading AI. Analyze these market news headlines and respond with exactly one word:
                    BULLISH - if news suggests market will go up
                    BEARISH - if news suggests market will go down
                    NEUTRAL - if news is mixed or unclear
                    
                    Headlines:
                    %s
                    
                    Response (one word only):
                    """.formatted(newsHeadlines);

            String response = callOllama(prompt, 10);
            
            // Extract sentiment from response
            String upper = response.trim().toUpperCase();
            if (upper.contains("BULLISH")) return Sentiment.BULLISH;
            if (upper.contains("BEARISH")) return Sentiment.BEARISH;
            return Sentiment.NEUTRAL;

        } catch (Exception e) {
            log.warn("⚠️ Ollama sentiment analysis failed: {}", e.getMessage());
            return Sentiment.NEUTRAL;
        }
    }

    /**
     * Optimizes strategy parameters based on backtest results.
     * 
     * @param strategyName Strategy name
     * @param backtestSummary Backtest summary text
     * @return AI recommendations for parameter tuning
     */
    public String optimizeStrategy(String strategyName, String backtestSummary) {
        try {
            String prompt = """
                    You are a quantitative trading expert. Analyze this backtest summary and provide specific recommendations to improve the strategy.
                    
                    Strategy: %s
                    
                    Backtest Results:
                    %s
                    
                    Provide recommendations in this format:
                    1. STOP LOSS: [specific recommendation with reasoning]
                    2. TAKE PROFIT: [specific recommendation with reasoning]
                    3. ENTRY TIMING: [specific recommendation with reasoning]
                    4. RISK MANAGEMENT: [specific recommendation with reasoning]
                    
                    Be specific with numbers (e.g., "Increase SL ATR multiplier from 2.5x to 3.0x").
                    """.formatted(strategyName, backtestSummary);

            return callOllama(prompt, 500);

        } catch (Exception e) {
            log.warn("⚠️ Ollama strategy optimization failed: {}", e.getMessage());
            return "Unable to analyze - Ollama may not be running";
        }
    }

    /**
     * Analyzes a specific trade to understand what went wrong/right.
     */
    public String analyzeTrade(String ticker, String strategy, String tradeDetails) {
        try {
            String prompt = """
                    Analyze this trade and explain what happened.
                    
                    Ticker: %s
                    Strategy: %s
                    Details: %s
                    
                    Provide:
                    1. Was this a valid signal? (YES/NO)
                    2. What went right or wrong?
                    3. How could the entry/exit be improved?
                    """.formatted(ticker, strategy, tradeDetails);

            return callOllama(prompt, 300);

        } catch (Exception e) {
            log.warn("⚠️ Ollama trade analysis failed: {}", e.getMessage());
            return "Unable to analyze";
        }
    }

    /**
     * Analyzes the learning report and provides AI-powered strategy recommendations.
     * This is the BRAAIN of the learning system - combines ML insights with AI reasoning.
     */
    public String analyzeLearningReport(String learningReport) {
        try {
            String prompt = """
                    You are a professional quantitative trading analyst. Review this automated learning report and provide specific, actionable recommendations.
                    
                    The system has been paper trading and learning from its mistakes. Your job is to:
                    1. Identify the most critical issues to fix
                    2. Suggest specific parameter adjustments for underperforming ticker+strategy combos
                    3. Recommend which patterns to disable/enhance
                    4. Suggest risk management improvements
                    
                    Learning Report:
                    %s
                    
                    Provide your analysis in this format:
                    
                    🔍 CRITICAL ISSUES:
                    [List top 3 issues that are causing the most losses]
                    
                    🎯 PATTERN OPTIMIZATION:
                    [Which patterns to enable/disable per ticker, with reasoning]
                    
                    ⚙️ PARAMETER TUNING:
                    [Specific ATR multiplier, RSI threshold, time filter adjustments]
                    
                    🛡️ RISK MANAGEMENT:
                    [Position sizing, stop loss, take profit improvements]
                    
                    📈 NEXT STEPS:
                    [What to test in the next backtest iteration]
                    
                    Be specific with numbers and prioritize changes that will have the biggest impact.
                    """.formatted(learningReport);

            return callOllama(prompt, 800);

        } catch (Exception e) {
            log.warn("⚠️ Ollama learning analysis failed: {}", e.getMessage());
            return "Unable to analyze learning report - Ollama may not be running";
        }
    }

    /**
     * Makes a call to Ollama API.
     */
    private String callOllama(String prompt, int maxTokens) throws IOException, InterruptedException {
        String body = """
                {
                    "model": "%s",
                    "prompt": "%s",
                    "stream": false,
                    "options": {
                        "temperature": 0.3,
                        "num_predict": %d
                    }
                }
                """.formatted(MODEL, prompt.replace("\n", "\\n"), maxTokens);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL + "/api/generate"))
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama returned " + response.statusCode());
        }

        // Extract response from JSON
        String responseBody = response.body();
        int start = responseBody.indexOf("\"response\":\"") + 12;
        int end = responseBody.indexOf("\",\"");
        if (start > 11 && end > start) {
            return responseBody.substring(start, end)
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"");
        }

        return responseBody;
    }

    /**
     * Checks if Ollama is available.
     */
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains(MODEL);

        } catch (Exception e) {
            return false;
        }
    }

    public enum Sentiment {
        BULLISH, BEARISH, NEUTRAL
    }
}
