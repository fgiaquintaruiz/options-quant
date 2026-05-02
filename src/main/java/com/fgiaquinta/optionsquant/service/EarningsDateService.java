package com.fgiaquinta.optionsquant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class EarningsDateService {

    private static final Path EARNINGS_CACHE_FILE = Path.of("data/earnings-dates.json");

    private final Map<String, LocalDate> earningsDates = new ConcurrentHashMap<>();
    private final YahooFinanceClient yahooClient;

    private volatile long lastRefreshTime = 0;
    private static final long REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000; // 24 hours

    public EarningsDateService(YahooFinanceClient yahooClient) {
        this.yahooClient = yahooClient;
    }

    public void refreshEarningsDates(List<String> tickers) {
        long now = System.currentTimeMillis();
        if (lastRefreshTime > 0 && (now - lastRefreshTime) < REFRESH_INTERVAL_MS) {
            log.debug("[Earnings] Cache still fresh ({}h ago), skipping refresh",
                    (now - lastRefreshTime) / (60 * 60 * 1000));
            return;
        }

        loadFromCache();

        if (earningsDates.isEmpty() || (now - lastRefreshTime) >= REFRESH_INTERVAL_MS) {
            log.info("[Earnings] Fetching upcoming earnings dates for {} tickers from Yahoo Finance...", tickers.size());
            fetchFromYahoo(tickers);
            saveToCache();
        }

        log.info("[Earnings] Loaded {} earnings dates. {} tickers have earnings in next 7 days.",
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

    public LocalDate getEarningsDate(String ticker) {
        LocalDate date = earningsDates.get(ticker.toUpperCase());
        if (date != null) {
            log.debug("Cache hit for {}: {}", ticker, date);
        }
        return date;
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

    private void fetchFromYahoo(List<String> tickers) {
        int loaded = 0;
        for (String ticker : tickers) {
            Optional<LocalDate> date = yahooClient.fetchEarningsDate(ticker);
            if (date.isPresent()) {
                earningsDates.put(ticker.toUpperCase(), date.get());
                loaded++;
            }
            // absent → keep existing entry (safe degradation)
        }
        log.info("[Earnings] Loaded {} dates from Yahoo Finance", loaded);
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
        Path tmp = null;
        try {
            Path dir = EARNINGS_CACHE_FILE.getParent();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            tmp = Files.createTempFile(dir, "earnings-dates", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), earningsDates);
            Files.move(tmp, EARNINGS_CACHE_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            log.warn("⚠️ [Earnings] Failed to save cache: {}", e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }
}
