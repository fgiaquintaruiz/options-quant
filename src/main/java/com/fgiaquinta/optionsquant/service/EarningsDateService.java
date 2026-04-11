package com.fgiaquinta.optionsquant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Earnings Date Service.
 *
 * Asks Ollama for upcoming earnings dates on startup and before backtests.
 * Caches the results and provides a simple API to check if a ticker has
 * earnings within a configurable window.
 *
 * Wall Street professionals avoid trading options near earnings because:
 * - Implied volatility crushes post-earnings (IV crush)
 * - Directional moves are unpredictable (binary events)
 * - Even correct direction calls can lose money due to IV crush
 */
@Slf4j
@Service
public class EarningsDateService {

    private static final String OLLAMA_URL = "http://localhost:11434";
    private static final String MODEL = "qwen2.5:7b";
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final Path EARNINGS_CACHE_FILE = Path.of("data/earnings-dates.json");

    private final Map<String, LocalDate> earningsDates = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    private volatile long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours

    /**
     * Loads earnings dates from Ollama. Called on startup.
     */
    public void refreshEarningsDates(List<String> tickers) {
        // Check if cache is still fresh
        long now = System.currentTimeMillis();
        if (lastRefreshTime > 0 && (now - lastRefreshTime) < REFRESH_INTERVAL_MS) {
            log.debug("📅 [Earnings] Cache still fresh ({}h ago), skipping refresh",
                    (now - lastRefreshTime) / (60 * 60 * 1000));
            return;
        }

        // Load from cache first
        loadFromCache();

        // Only refresh if cache is empty or stale
        if (earningsDates.isEmpty() || (now - lastRefreshTime) >= REFRESH_INTERVAL_MS) {
            log.info("📅 [Earnings] Fetching upcoming earnings dates for {} tickers from Ollama...", tickers.size());
            fetchFromOllama(tickers);
            saveToCache();
        }

        log.info("📅 [Earnings] Loaded {} earnings dates. {} tickers have earnings in next 7 days.",
                earningsDates.size(), getTickersWithEarnings(7).size());
    }

    /**
     * Checks if a ticker has earnings within the specified days.
     *
     * @param ticker Ticker symbol
     * @param daysAhead Number of days to look ahead
     * @return true if earnings are within the window (should avoid trading)
     */
    public boolean hasEarningsSoon(String ticker, int daysAhead) {
        LocalDate earningsDate = earningsDates.get(ticker.toUpperCase());
        if (earningsDate == null) return false;

        LocalDate today = LocalDate.now();
        LocalDate window = today.plusDays(daysAhead);

        // Earnings today or within the window
        return !earningsDate.isBefore(today) && !earningsDate.isAfter(window);
    }

    /**
     * Gets all tickers with earnings within the specified days.
     */
    public List<String> getTickersWithEarnings(int daysAhead) {
        LocalDate today = LocalDate.now();
        LocalDate window = today.plusDays(daysAhead);

        return earningsDates.entrySet().stream()
                .filter(e -> !e.getValue().isBefore(today) && !e.getValue().isAfter(window))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * Gets the specific earnings date for a ticker.
     */
    public LocalDate getEarningsDate(String ticker) {
        return earningsDates.get(ticker.toUpperCase());
    }

    /**
     * Gets all cached earnings dates.
     */
    public Map<String, LocalDate> getAllEarningsDates() {
        return Map.copyOf(earningsDates);
    }

    /**
     * Gets a human-readable earnings report.
     */
    public String getEarningsReport() {
        if (earningsDates.isEmpty()) return "No earnings dates loaded.";

        LocalDate today = LocalDate.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM dd");

        List<Map.Entry<String, LocalDate>> upcoming = earningsDates.entrySet().stream()
                .filter(e -> !e.getValue().isBefore(today))
                .sorted(Map.Entry.comparingByValue())
                .limit(20)
                .toList();

        if (upcoming.isEmpty()) return "No upcoming earnings dates in cache.";

        StringBuilder sb = new StringBuilder();
        sb.append("📅 Upcoming Earnings Dates\n");
        sb.append("=" .repeat(50)).append("\n");

        for (Map.Entry<String, LocalDate> entry : upcoming) {
            String daysUntil = String.valueOf(java.time.temporal.ChronoUnit.DAYS.between(today, entry.getValue()));
            sb.append(String.format("  %-8s %s  (%s days)%n", entry.getKey(), entry.getValue().format(fmt), daysUntil));
        }

        return sb.toString();
    }

    /**
     * Manually set an earnings date (for corrections or manual entry).
     */
    public void setEarningsDate(String ticker, LocalDate date) {
        earningsDates.put(ticker.toUpperCase(), date);
        saveToCache();
        log.info("📅 [Earnings] Set {} earnings to {}", ticker, date);
    }

    /**
     * Clears the earnings cache.
     */
    public void clearCache() {
        earningsDates.clear();
        lastRefreshTime = 0;
        try {
            Files.deleteIfExists(EARNINGS_CACHE_FILE);
        } catch (IOException e) {
            // ignore
        }
        log.info("📅 [Earnings] Cache cleared");
    }

    // ===== Ollama Integration =====

    private void fetchFromOllama(List<String> tickers) {
        try {
            String tickerList = String.join(", ", tickers);
            String prompt = String.format("""
                    You are a financial data assistant. Return ONLY a JSON object with the upcoming earnings dates
                    for the following US stock tickers: %s.

                    Format:
                    {"TICKER": "YYYY-MM-DD", ...}

                    Rules:
                    - Use the NEXT known earnings date for each ticker
                    - If you don't know the date, omit that ticker from the response
                    - Return ONLY valid JSON, no explanations or markdown formatting
                    - Dates must be in the future (after today)
                    """, tickerList);

            String body = """
                    {
                        "model": "%s",
                        "prompt": "%s",
                        "stream": false,
                        "options": {
                            "temperature": 0.1,
                            "num_predict": 2000
                        }
                    }
                    """.formatted(MODEL, prompt.replace("\n", "\\n"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("⚠️ [Earnings] Ollama returned {}: {}", response.statusCode(), response.body());
                return;
            }

            String responseBody = response.body();
            int start = responseBody.indexOf("\"response\":\"") + 12;
            int end = responseBody.indexOf("\",\"");
            if (start <= 11 || end <= start) {
                log.warn("⚠️ [Earnings] Failed to parse Ollama response");
                return;
            }

            String jsonStr = responseBody.substring(start, end)
                    .replace("\\n", "")
                    .replace("\\\"", "\"")
                    .trim();

            // Parse the JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> dates = mapper.readValue(jsonStr, Map.class);

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            int loaded = 0;
            for (Map.Entry<String, String> entry : dates.entrySet()) {
                try {
                    String ticker = entry.getKey().toUpperCase().trim();
                    LocalDate date = LocalDate.parse(entry.getValue().trim(), fmt);
                    earningsDates.put(ticker, date);
                    loaded++;
                } catch (Exception e) {
                    log.debug("⚠️ [Earnings] Failed to parse earnings date for {}: {}", entry.getKey(), entry.getValue());
                }
            }

            lastRefreshTime = System.currentTimeMillis();
            log.info("📅 [Earnings] Loaded {} earnings dates from Ollama", loaded);

        } catch (Exception e) {
            log.warn("⚠️ [Earnings] Failed to fetch earnings from Ollama: {}", e.getMessage());
        }
    }

    // ===== Cache Persistence =====

    @SuppressWarnings("unchecked")
    private void loadFromCache() {
        if (!Files.exists(EARNINGS_CACHE_FILE)) return;

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            Map<String, String> data = mapper.readValue(EARNINGS_CACHE_FILE.toFile(), Map.class);
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

            for (Map.Entry<String, String> entry : data.entrySet()) {
                try {
                    earningsDates.put(entry.getKey(), LocalDate.parse(entry.getValue(), fmt));
                } catch (Exception e) {
                    // skip invalid
                }
            }

            lastRefreshTime = System.currentTimeMillis();
            log.debug("📅 [Earnings] Loaded {} dates from cache", earningsDates.size());

        } catch (IOException e) {
            log.debug("📅 [Earnings] Cache file not readable: {}", e.getMessage());
        }
    }

    private void saveToCache() {
        try {
            if (!Files.exists(EARNINGS_CACHE_FILE.getParent())) {
                Files.createDirectories(EARNINGS_CACHE_FILE.getParent());
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            mapper.writerWithDefaultPrettyPrinter().writeValue(EARNINGS_CACHE_FILE.toFile(), earningsDates);

        } catch (IOException e) {
            log.warn("⚠️ [Earnings] Failed to save cache: {}", e.getMessage());
        }
    }
}
