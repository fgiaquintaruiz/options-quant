package com.fgiaquinta.optionsquant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticker Memory: Tracks historical performance per ticker across sessions.
 *
 * Wall Street professionals don't treat all tickers equally — they know which
 * names work with their strategies and which don't. This service:
 *
 * 1. Records every trade outcome (win/loss, PnL, strategy used)
 * 2. Calculates per-ticker metrics: win rate, avg PnL, profit factor, streak
 * 3. Adjusts position sizing: boost winners, reduce losers, block chronic losers
 * 4. Persists to disk between sessions
 *
 * File format: data/ticker-memory.json
 */
@Slf4j
@Service
public class TickerMemory {

    private static final Path MEMORY_FILE = Path.of("data/ticker-memory.json");
    private static final int MIN_TRADES_FOR_CONFIDENCE = 3;
    private static final double BLOCK_WIN_RATE_THRESHOLD = 0.30;  // Block if < 30% win rate
    private static final double BOOST_WIN_RATE_THRESHOLD = 0.70;  // Boost if > 70% win rate
    private static final double MAX_SIZE_MULTIPLIER = 2.0;        // Max 2x position size for winners
    private static final double MIN_SIZE_MULTIPLIER = 0.25;       // Min 0.25x for losers

    private final Map<String, TickerStats> memory = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public TickerMemory() {
        mapper.registerModule(new JavaTimeModule());
        load();
    }

    /**
     * Records a completed trade outcome.
     */
    public void recordTrade(String ticker, String strategy, boolean isWin, double pnl, double maxDrawdown) {
        TickerStats stats = memory.computeIfAbsent(ticker, TickerStats::new);

        stats.totalTrades++;
        if (isWin) {
            stats.wins++;
            stats.currentStreak = stats.currentStreak > 0 ? stats.currentStreak + 1 : 1;
            stats.bestStreak = Math.max(stats.bestStreak, stats.currentStreak);
        } else {
            stats.losses++;
            stats.currentStreak = stats.currentStreak < 0 ? stats.currentStreak - 1 : -1;
            stats.worstStreak = Math.min(stats.worstStreak, stats.currentStreak);
        }

        stats.totalPnl += pnl;
        stats.maxDrawdown = Math.max(stats.maxDrawdown, maxDrawdown);

        if (isWin) stats.totalWinsPnl += pnl;
        else stats.totalLossesPnl += Math.abs(pnl);

        stats.lastTradeTime = ZonedDateTime.now();
        stats.lastStrategy = strategy;

        // Log significant events
        if (stats.totalTrades % 5 == 0) {
            log.info("🧠 [Memory] {} update: {} trades, {:.1f}% WR, PnL ${:.2f}",
                    ticker, stats.totalTrades, stats.getWinRate() * 100, stats.totalPnl);
        }

        save();
    }

    /**
     * Gets the position size multiplier for a ticker based on historical performance.
     *
     * @return Multiplier: 0.25x (chronic loser) to 2.0x (consistent winner)
     *         Returns 1.0x if insufficient data
     */
    public double getPositionSizeMultiplier(String ticker) {
        TickerStats stats = memory.get(ticker);
        if (stats == null || stats.totalTrades < MIN_TRADES_FOR_CONFIDENCE) {
            return 1.0;  // No data, use normal size
        }

        double winRate = stats.getWinRate();
        double profitFactor = stats.getProfitFactor();

        // Chronic losers: drastically reduce size
        if (winRate < BLOCK_WIN_RATE_THRESHOLD && stats.totalTrades >= 5) {
            return MIN_SIZE_MULTIPLIER;
        }

        // Consistent winners: boost size
        if (winRate >= BOOST_WIN_RATE_THRESHOLD && profitFactor > 1.5) {
            double bonus = Math.min(MAX_SIZE_MULTIPLIER, 1.0 + (winRate - BOOST_WIN_RATE_THRESHOLD) * 3);
            return bonus;
        }

        // Slightly above average
        if (winRate > 0.55 && profitFactor > 1.0) {
            return 1.25;
        }

        // Slightly below average
        if (winRate < 0.45) {
            return 0.75;
        }

        return 1.0;  // Average performance
    }

    /**
     * Checks if a ticker should be blocked from trading.
     * A ticker is blocked if it has >= 5 trades and < 30% win rate.
     */
    public boolean isBlocked(String ticker) {
        TickerStats stats = memory.get(ticker);
        if (stats == null) return false;

        if (stats.totalTrades >= 5 && stats.getWinRate() < BLOCK_WIN_RATE_THRESHOLD) {
            log.warn("🚫 [Memory] BLOCKED {} — {} trades, {:.1f}% win rate, PnL ${:.2f}",
                    ticker, stats.totalTrades, stats.getWinRate() * 100, stats.totalPnl);
            return true;
        }

        return false;
    }

    /**
     * Gets stats for a specific ticker.
     */
    public TickerStats getStats(String ticker) {
        return memory.get(ticker);
    }

    /**
     * Gets all ticker stats.
     */
    public Map<String, TickerStats> getAllStats() {
        return Map.copyOf(memory);
    }

    /**
     * Gets a summary report of all tracked tickers.
     */
    public String getSummaryReport() {
        if (memory.isEmpty()) return "No ticker memory data yet.";

        StringBuilder sb = new StringBuilder();
        sb.append("🧠 Ticker Memory Report\n");
        sb.append("=" .repeat(80)).append("\n");
        sb.append(String.format("%-8s %6s %8s %10s %10s %8s %12s %s%n",
                "Ticker", "Trades", "Win%", "PnL", "PF", "Streak", "Multiplier", "Last Strategy"));
        sb.append("-".repeat(80)).append("\n");

        memory.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().totalPnl, a.getValue().totalPnl))
                .forEach(entry -> {
                    String ticker = entry.getKey();
                    TickerStats stats = entry.getValue();
                    String blocked = isBlocked(ticker) ? " 🚫" : "";
                    sb.append(String.format("%-8s %6d %7.1f%% $%9.2f %9.2f %5d %12.2fx %s%s%n",
                            ticker, stats.totalTrades, stats.getWinRate() * 100,
                            stats.totalPnl, stats.getProfitFactor(), stats.currentStreak,
                            getPositionSizeMultiplier(ticker),
                            stats.lastStrategy != null ? stats.lastStrategy : "N/A",
                            blocked));
                });

        return sb.toString();
    }

    /**
     * Resets memory for a specific ticker.
     */
    public void resetTicker(String ticker) {
        memory.remove(ticker);
        save();
        log.info("🧹 [Memory] Reset stats for {}", ticker);
    }

    /**
     * Resets all memory.
     */
    public void resetAll() {
        memory.clear();
        save();
        log.info("🧹 [Memory] Reset all ticker memory");
    }

    // ===== Persistence =====

    private void load() {
        if (!Files.exists(MEMORY_FILE)) {
            log.info("🧠 [Memory] No existing memory file found, starting fresh");
            return;
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> data = mapper.readValue(MEMORY_FILE.toFile(), Map.class);

            for (Map.Entry<String, Map<String, Object>> entry : data.entrySet()) {
                TickerStats stats = new TickerStats(entry.getKey());
                Map<String, Object> m = entry.getValue();
                stats.totalTrades = ((Number) m.getOrDefault("totalTrades", 0)).intValue();
                stats.wins = ((Number) m.getOrDefault("wins", 0)).intValue();
                stats.losses = ((Number) m.getOrDefault("losses", 0)).intValue();
                stats.totalPnl = ((Number) m.getOrDefault("totalPnl", 0.0)).doubleValue();
                stats.totalWinsPnl = ((Number) m.getOrDefault("totalWinsPnl", 0.0)).doubleValue();
                stats.totalLossesPnl = ((Number) m.getOrDefault("totalLossesPnl", 0.0)).doubleValue();
                stats.maxDrawdown = ((Number) m.getOrDefault("maxDrawdown", 0.0)).doubleValue();
                stats.currentStreak = ((Number) m.getOrDefault("currentStreak", 0)).intValue();
                stats.bestStreak = ((Number) m.getOrDefault("bestStreak", 0)).intValue();
                stats.worstStreak = ((Number) m.getOrDefault("worstStreak", 0)).intValue();
                stats.lastStrategy = (String) m.get("lastStrategy");

                String lastTradeStr = (String) m.get("lastTradeTime");
                if (lastTradeStr != null) {
                    stats.lastTradeTime = ZonedDateTime.parse(lastTradeStr);
                }

                memory.put(entry.getKey(), stats);
            }

            log.info("🧠 [Memory] Loaded {} tickers from {}", memory.size(), MEMORY_FILE);

        } catch (IOException e) {
            log.warn("⚠️ [Memory] Failed to load memory file: {}", e.getMessage());
        }
    }

    private void save() {
        try {
            if (!Files.exists(MEMORY_FILE.getParent())) {
                Files.createDirectories(MEMORY_FILE.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(MEMORY_FILE.toFile(), memory);
        } catch (IOException e) {
            log.warn("⚠️ [Memory] Failed to save memory file: {}", e.getMessage());
        }
    }

    /**
     * Per-ticker performance statistics.
     */
    public static class TickerStats {
        public final String ticker;
        public int totalTrades = 0;
        public int wins = 0;
        public int losses = 0;
        public double totalPnl = 0;
        public double totalWinsPnl = 0;
        public double totalLossesPnl = 0;
        public double maxDrawdown = 0;
        public int currentStreak = 0;
        public int bestStreak = 0;
        public int worstStreak = 0;
        public String lastStrategy;
        public ZonedDateTime lastTradeTime;

        TickerStats(String ticker) {
            this.ticker = ticker;
        }

        public double getWinRate() {
            return totalTrades > 0 ? (double) wins / totalTrades : 0;
        }

        public double getProfitFactor() {
            if (totalLossesPnl == 0) return totalWinsPnl > 0 ? Double.POSITIVE_INFINITY : 0;
            return totalWinsPnl / totalLossesPnl;
        }

        public double getAvgWin() {
            return wins > 0 ? totalWinsPnl / wins : 0;
        }

        public double getAvgLoss() {
            return losses > 0 ? totalLossesPnl / losses : 0;
        }

        @Override
        public String toString() {
            return String.format("%s: %d trades, %.1f%% WR, PF=%.2f, PnL=$%.2f, streak=%d",
                    ticker, totalTrades, getWinRate() * 100, getProfitFactor(), totalPnl, currentStreak);
        }
    }
}
