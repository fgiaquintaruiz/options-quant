package com.fgiaquinta.optionsquant.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticker Memory: Tracks historical performance per ticker across sessions.
 *
 * Wall Street professionals don't treat all tickers equally — they know which
 * names work with their strategies and which don't. This service:
 *
 * 1. Records every trade outcome (win/loss, PnL, strategy used, patterns, context)
 * 2. Calculates per-ticker metrics: win rate, avg PnL, profit factor, streak
 * 3. Learns which patterns work per ticker+strategy combination
 * 4. Adjusts position sizing: boost winners, reduce losers
 * 5. Provides actionable insights instead of just blocking
 * 6. Persists to disk between sessions
 *
 * File format: data/ticker-memory.json
 */
@Slf4j
@Service
public class TickerMemory {

    private static final Path MEMORY_FILE = Path.of("data/ticker-memory.json");
    private static final int MIN_TRADES_FOR_CONFIDENCE = 3;
    private static final int SAVE_INTERVAL = 100;

    // Pre-compiled DecimalFormat instances for formatting (thread-safe for read-only use)
    private static final DecimalFormat FMT_1D = new DecimalFormat("#.0");
    private static final DecimalFormat FMT_2D = new DecimalFormat("#.00");
    private static final DecimalFormat FMT_0D = new DecimalFormat("#");
    private static final DecimalFormat FMT_PCT_1D = new DecimalFormat("#.0");
    private static final DecimalFormat FMT_SIGNED_2D = new DecimalFormat("+0.00;-0.00");

    // Fast formatting helpers that avoid String.format's regex overhead
    private static String fmt1(double v) {
        return FMT_1D.format(v);
    }
    private static String fmt2(double v) {
        return FMT_2D.format(v);
    }
    private static String fmt0(double v) {
        return FMT_0D.format(v);
    }
    private static String padRight(String s, int w) {
        if (s.length() >= w) return s.substring(0, Math.min(s.length(), w));
        StringBuilder sb = new StringBuilder(w);
        sb.append(s);
        for (int i = s.length(); i < w; i++) sb.append(' ');
        return sb.toString();
    }
    private static String padLeft(String s, int w) {
        if (s.length() >= w) return s.substring(s.length() - w);
        StringBuilder sb = new StringBuilder(w);
        for (int i = 0; i < w - s.length(); i++) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    // Per-ticker aggregate stats
    private final Map<String, TickerStats> memory = new ConcurrentHashMap<>();

    // Per-ticker, per-strategy learned profiles
    private final Map<String, TickerStrategyProfile> strategyProfiles = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();

    // Debounce counter for save operations
    private int tradeCounter = 0;

    public TickerMemory() {
        mapper.registerModule(new JavaTimeModule());
        load();
    }

    /**
     * Records a completed trade with full context for learning.
     * This is the MAIN entry point for trade recording.
     */
    public void recordTrade(String ticker, String strategy, String pattern, boolean isWin, 
                           double pnl, double maxDrawdown, double maxRunup,
                           double atrAtEntry, double vixAtEntry, int entryHour,
                           String marketTrend, Map<String, Object> entryContext) {
        // Update aggregate ticker stats
        TickerStats stats = memory.computeIfAbsent(ticker, TickerStats::new);
        stats.recordTrade(strategy, isWin, pnl, maxDrawdown, maxRunup);

        // Update strategy-specific profile for learning
        String profileKey = ticker + "::" + strategy;
        TickerStrategyProfile profile = strategyProfiles.computeIfAbsent(
                profileKey, k -> new TickerStrategyProfile(ticker, strategy));
        
        String lossCategory = !isWin ? categorizeLoss(pnl, maxDrawdown, maxRunup, atrAtEntry) : null;
        profile.recordTrade(pattern, isWin, pnl, lossCategory, entryHour, vixAtEntry, marketTrend);

        // Log significant events
        if (stats.totalTrades % 5 == 0) {
            log.info("🧠 [Memory] {} update: {} trades, {}% WR, PnL ${}, confidence {}%",
                    ticker, stats.totalTrades, FMT_PCT_1D.format(stats.getWinRate() * 100),
                    FMT_2D.format(stats.totalPnl),
                    FMT_0D.format(profile.confidenceScore));
        }

        // Log loss categorization for learning
        if (!isWin && lossCategory != null) {
            log.debug("📝 [Memory] {} loss categorized as: {} (strategy: {}, PnL: ${})",
                    ticker, lossCategory, strategy, FMT_2D.format(pnl));
        }

        tradeCounter++;
        if (tradeCounter % SAVE_INTERVAL == 0) {
            save();
        }
    }

    /**
     * Simplified recordTrade for backward compatibility.
     */
    public void recordTrade(String ticker, String strategy, boolean isWin, double pnl, double maxDrawdown) {
        recordTrade(ticker, strategy, "unknown", isWin, pnl, maxDrawdown, 0, 
                   0.0, 0.0, 0, "neutral", Map.of());
    }

    /**
     * Categorizes loss based on trade characteristics.
     */
    private String categorizeLoss(double pnl, double maxDrawdown, double maxRunup, double atrAtEntry) {
        if (pnl >= 0) return null;
        
        if (maxRunup > Math.abs(pnl) * 0.5) {
            return "TIMING"; // Was in profit, then reversed
        }
        if (atrAtEntry > 0 && maxDrawdown > atrAtEntry * 100 * 3) {
            return "VOLATILITY"; // Stopped out by noise
        }
        return "MOMENTUM"; // Against the trend
    }

    /**
     * Gets the position size multiplier for a ticker based on historical performance.
     * Uses the BEST strategy profile for that ticker if multiple exist.
     *
     * @return Multiplier: 0.25x (chronic loser) to 2.0x (consistent winner)
     *         Returns 1.0x if insufficient data
     */
    public double getPositionSizeMultiplier(String ticker) {
        // Find all profiles for this ticker
        List<TickerStrategyProfile> profiles = strategyProfiles.entrySet().stream()
                .filter(e -> e.getKey().startsWith(ticker + "::"))
                .map(Map.Entry::getValue)
                .filter(p -> p.totalTrades >= MIN_TRADES_FOR_CONFIDENCE)
                .toList();

        if (profiles.isEmpty()) {
            // Fall back to aggregate stats
            TickerStats stats = memory.get(ticker);
            if (stats == null || stats.totalTrades < MIN_TRADES_FOR_CONFIDENCE) {
                return 1.0;
            }
            return calculateAggregateMultiplier(stats);
        }

        // Use weighted average of all profiles (weighted by trade count)
        double totalWeight = 0;
        double weightedMultiplier = 0;
        for (TickerStrategyProfile profile : profiles) {
            double weight = profile.totalTrades;
            totalWeight += weight;
            weightedMultiplier += weight * profile.getPositionSizeMultiplier();
        }

        return totalWeight > 0 ? weightedMultiplier / totalWeight : 1.0;
    }

    private double calculateAggregateMultiplier(TickerStats stats) {
        double winRate = stats.getWinRate();
        double profitFactor = stats.getProfitFactor();

        // Consistent winners: boost size
        if (winRate >= 0.70 && profitFactor > 1.5) {
            return Math.min(2.0, 1.0 + (winRate - 0.70) * 3);
        }

        // Slightly above average
        if (winRate > 0.55 && profitFactor > 1.0) {
            return 1.25;
        }

        // Slightly below average
        if (winRate < 0.45) {
            return 0.75;
        }

        return 1.0;
    }

    /**
     * Checks if a ticker should be blocked from trading.
     * NOW: Only blocks if confidence is EXTREMELY low (< 10%) after 10+ trades.
     * This is much more lenient than before - we want to learn, not block.
     */
    public boolean isBlocked(String ticker) {
        // Check strategy profiles
        List<TickerStrategyProfile> profiles = strategyProfiles.entrySet().stream()
                .filter(e -> e.getKey().startsWith(ticker + "::"))
                .map(Map.Entry::getValue)
                .filter(p -> p.totalTrades >= 10)
                .toList();

        // Only block if ALL profiles have extremely low confidence
        if (!profiles.isEmpty() && profiles.stream().allMatch(p -> p.confidenceScore < 10)) {
            log.warn("🚫 [Memory] BLOCKED {} — all strategies have < 10% confidence after 10+ trades", ticker);
            return true;
        }

        // Fall back to aggregate stats (very lenient)
        TickerStats stats = memory.get(ticker);
        if (stats != null && stats.totalTrades >= 15 && stats.getWinRate() < 0.20) {
            log.warn("🚫 [Memory] BLOCKED {} — {} trades, {}% win rate, PnL ${}",
                    ticker, stats.totalTrades, FMT_PCT_1D.format(stats.getWinRate() * 100),
                    FMT_2D.format(stats.totalPnl));
            return true;
        }

        return false;
    }

    /**
     * Gets the strategy profile for a specific ticker+strategy.
     * Useful for checking pattern filters and entry conditions.
     */
    public TickerStrategyProfile getStrategyProfile(String ticker, String strategy) {
        return strategyProfiles.get(ticker + "::" + strategy);
    }

    /**
     * Checks if a pattern is allowed for a ticker+strategy based on learned performance.
     */
    public boolean isPatternAllowed(String ticker, String strategy, String pattern) {
        TickerStrategyProfile profile = getStrategyProfile(ticker, strategy);
        if (profile == null || profile.totalTrades < 3) {
            return true; // No data yet, allow
        }
        return profile.isPatternAllowed(pattern);
    }

    /**
     * Checks if current conditions allow entry based on learned filters.
     */
    public boolean canEnter(String ticker, String strategy, String pattern,
                           LocalTime currentTime, double currentVix, String currentMarketTrend) {
        TickerStrategyProfile profile = getStrategyProfile(ticker, strategy);
        if (profile == null || profile.totalTrades < 3) {
            return true; // No filters learned yet
        }

        // Check pattern filter
        if (!profile.isPatternAllowed(pattern)) {
            log.debug("🚫 [Memory] Pattern '{}' disabled for {}+{}", pattern, ticker, strategy);
            return false;
        }

        // Check entry conditions
        if (!profile.canEnter(currentTime, currentVix, currentMarketTrend)) {
            log.debug("🚫 [Memory] Entry conditions not met for {}+{} (time={}, VIX={}, trend={})",
                    ticker, strategy, currentTime, currentVix, currentMarketTrend);
            return false;
        }

        return true;
    }

    /**
     * Gets stats for a specific ticker.
     */
    public TickerStats getStats(String ticker) {
        return memory.get(ticker);
    }

    /**
     * 0–100 score from historical paper/live outcomes for hybrid scan ordering (higher = scan earlier).
     * Returns 0 when there is no meaningful history.
     */
    public double getLearningPriorityScore(String ticker) {
        String key = ticker != null ? ticker.toUpperCase() : "";
        TickerStats s = memory.get(key);
        if (s == null || s.totalTrades < 1) {
            return 0;
        }
        double wr = s.getWinRate();
        double pf = Math.min(3.0, Math.max(0, s.getProfitFactor()));
        return Math.min(100.0, wr * 60.0 + pf * 15.0);
    }

    /**
     * Gets all ticker stats.
     */
    public Map<String, TickerStats> getAllStats() {
        return Map.copyOf(memory);
    }

    /**
     * Gets all strategy profiles.
     */
    public Map<String, TickerStrategyProfile> getAllProfiles() {
        return Map.copyOf(strategyProfiles);
    }

    /**
     * Gets a comprehensive learning report.
     */
    public String getLearningReport() {
        if (memory.isEmpty() && strategyProfiles.isEmpty()) {
            return "No ticker memory data yet. Run a backtest to start learning!";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🧠 Ticker Memory & Learning Report\n");
        sb.append("=".repeat(100)).append("\n");

        // Aggregate stats
        sb.append("\n📊 AGGREGATE TICKER STATS\n");
        sb.append("-".repeat(100)).append("\n");
        sb.append(padRight("Ticker", 8)).append(padRight("Trades", 6))
          .append(padRight("Win%", 8)).append(padRight("PnL", 10))
          .append(padRight("PF", 10)).append(padRight("Streak", 8))
          .append(padRight("Multiplier", 12)).append("Last Strategy\n");
        sb.append("-".repeat(100)).append("\n");

        memory.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue().totalPnl, a.getValue().totalPnl))
                .forEach(entry -> {
                    String ticker = entry.getKey();
                    TickerStats stats = entry.getValue();
                    String blocked = isBlocked(ticker) ? " 🚫" : "";
                    sb.append(padRight(ticker, 8))
                      .append(padLeft(String.valueOf(stats.totalTrades), 6))
                      .append(padRight(fmt1(stats.getWinRate() * 100) + "%", 8))
                      .append(" $").append(padLeft(fmt2(stats.totalPnl), 9))
                      .append(" ").append(padLeft(fmt2(stats.getProfitFactor()), 9))
                      .append(" ").append(padLeft(String.valueOf(stats.currentStreak), 5))
                      .append(" ").append(padLeft(fmt2(getPositionSizeMultiplier(ticker)) + "x", 12))
                      .append(stats.lastStrategy != null ? stats.lastStrategy : "N/A")
                      .append(blocked).append("\n");
                });

        // Strategy profiles
        if (!strategyProfiles.isEmpty()) {
            sb.append("\n🎯 STRATEGY PROFILES (Learned Adjustments)\n");
            sb.append("-".repeat(100)).append("\n");
            sb.append(padRight("Ticker", 15)).append(padRight("Strategy", 20))
              .append(padRight("Trades", 6)).append(padRight("Win%", 8))
              .append(padRight("PnL", 10)).append(padRight("Confidence", 10))
              .append(padRight("Size Mult", 8)).append("Top Loss\n");
            sb.append("-".repeat(100)).append("\n");

            strategyProfiles.values().stream()
                    .filter(p -> p.totalTrades > 0)
                    .sorted((a, b) -> Double.compare(b.confidenceScore, a.confidenceScore))
                    .forEach(profile -> {
                        sb.append(padRight(profile.ticker, 15))
                          .append(padRight(profile.strategy, 20))
                          .append(padLeft(String.valueOf(profile.totalTrades), 6))
                          .append(padRight(fmt1(profile.getWinRate() * 100) + "%", 8))
                          .append(" $").append(padLeft(fmt2(profile.totalPnl), 9))
                          .append(" ").append(padLeft(fmt0(profile.confidenceScore) + "%", 10))
                          .append(" ").append(padLeft(fmt2(profile.getPositionSizeMultiplier()) + "x", 8))
                          .append(profile.getTopLossCategory()).append("\n");

                        // Show pattern performance if available
                        if (!profile.patternPerformance.isEmpty()) {
                            sb.append("  Patterns: ");
                            profile.patternPerformance.values().stream()
                                    .sorted((a, b) -> Double.compare(b.getWinRate(), a.getWinRate()))
                                    .forEach(ps -> {
                                        String status = profile.enabledPatterns.contains(ps.pattern) ? "✅" :
                                                       profile.disabledPatterns.contains(ps.pattern) ? "❌" : "⏳";
                                        sb.append(status).append(" ").append(ps.pattern)
                                          .append(" (").append(fmt0(ps.getWinRate() * 100)).append("%, ")
                                          .append(ps.totalTrades).append(" trades), ");
                                    });
                            sb.append("\n");
                        }
                    });
        }

        // Loss categorization summary
        sb.append("\n📝 LOSS CATEGORIZATION SUMMARY\n");
        sb.append("-".repeat(100)).append("\n");
        Map<String, Integer> totalLosses = new HashMap<>();
        strategyProfiles.values().forEach(p ->
            p.lossCategories.forEach((cat, count) -> totalLosses.merge(cat, count, Integer::sum))
        );
        int totalLossCount = totalLosses.values().stream().mapToInt(Integer::intValue).sum();
        final int finalTotalLossCount = totalLossCount;
        totalLosses.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> {
                    double pct = finalTotalLossCount > 0 ? (double) e.getValue() / finalTotalLossCount * 100 : 0;
                    sb.append("  ").append(padRight(e.getKey(), 20))
                      .append(": ").append(e.getValue()).append(" losses (")
                      .append(fmt1(pct)).append("%)\n");
                });

        return sb.toString();
    }

    /**
     * Resets memory for a specific ticker.
     */
    public void resetTicker(String ticker) {
        memory.remove(ticker);
        strategyProfiles.keySet().removeIf(k -> k.startsWith(ticker + "::"));
        save();
        log.info("🧹 [Memory] Reset stats for {}", ticker);
    }

    /**
     * Resets all memory.
     */
    public void resetAll() {
        memory.clear();
        strategyProfiles.clear();
        save();
        log.info("🧹 [Memory] Reset all ticker memory");
    }

    /**
     * Flushes any remaining unsaved trades to disk.
     * Should be called at the end of backtests/learning loops to ensure
     * all trades are persisted even if the total count isn't a multiple of SAVE_INTERVAL.
     */
    public void flush() {
        if (tradeCounter % SAVE_INTERVAL != 0) {
            save();
        }
        tradeCounter = 0;
    }

    // ===== Persistence =====

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(MEMORY_FILE)) {
            log.info("🧠 [Memory] No existing memory file found, starting fresh");
            return;
        }

        try {
            Map<String, Object> data = mapper.readValue(MEMORY_FILE.toFile(),
                    new TypeReference<Map<String, Object>>() {});

            // Load aggregate stats
            if (data.containsKey("memory")) {
                Map<String, Map<String, Object>> memoryData =
                        (Map<String, Map<String, Object>>) data.get("memory");
                for (Map.Entry<String, Map<String, Object>> entry : memoryData.entrySet()) {
                    try {
                        TickerStats stats = new TickerStats(entry.getKey());
                        Map<String, Object> m = entry.getValue();
                        loadTickerStats(stats, m);
                        memory.put(entry.getKey(), stats);
                    } catch (Exception e) {
                        log.warn("⚠️ [Memory] Skipping corrupt ticker entry '{}': {}", entry.getKey(), e.getMessage());
                    }
                }
            }

            // Load strategy profiles (new format only)
            if (data.containsKey("profiles")) {
                try {
                    List<Map<String, Object>> profilesData =
                            (List<Map<String, Object>>) data.get("profiles");
                    for (Map<String, Object> profileMap : profilesData) {
                        TickerStrategyProfile profile = loadStrategyProfile(profileMap);
                        if (profile != null) {
                            strategyProfiles.put(profile.ticker + "::" + profile.strategy, profile);
                        }
                    }
                } catch (Exception e) {
                    log.warn("⚠️ [Memory] Failed to load strategy profiles, starting profiles fresh: {}", e.getMessage());
                }
            }

            log.info("🧠 [Memory] Loaded {} tickers and {} profiles from {}",
                    memory.size(), strategyProfiles.size(), MEMORY_FILE);

        } catch (Exception e) {
            // If the file is corrupt or old format, start fresh
            log.warn("⚠️ [Memory] Failed to load memory file ({}), starting fresh. Consider deleting {}",
                    e.getMessage(), MEMORY_FILE);
            // Try to backup the old file
            try {
                Path backup = MEMORY_FILE.resolveSibling("ticker-memory.json.bak");
                Files.copy(MEMORY_FILE, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("🧠 [Memory] Backed up old file to {}", backup);
            } catch (IOException ioEx) {
                log.debug("Could not backup old file: {}", ioEx.getMessage());
            }
        }
    }

    private void loadTickerStats(TickerStats stats, Map<String, Object> m) {
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
        
        // Defensive: handle case where lastStrategy might not be a String
        Object lastStrategyObj = m.get("lastStrategy");
        if (lastStrategyObj instanceof String) {
            stats.lastStrategy = (String) lastStrategyObj;
        } else if (lastStrategyObj != null) {
            stats.lastStrategy = String.valueOf(lastStrategyObj);
        }

        Object lastTradeObj = m.get("lastTradeTime");
        if (lastTradeObj instanceof String) {
            try {
                stats.lastTradeTime = ZonedDateTime.parse((String) lastTradeObj);
            } catch (Exception e) {
                log.debug("Failed to parse lastTradeTime for {}: {}", stats.ticker, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private TickerStrategyProfile loadStrategyProfile(Map<String, Object> m) {
        String ticker = (String) m.get("ticker");
        String strategy = (String) m.get("strategy");
        if (ticker == null || strategy == null) return null;

        TickerStrategyProfile profile = new TickerStrategyProfile(ticker, strategy);
        profile.totalTrades = ((Number) m.getOrDefault("totalTrades", 0)).intValue();
        profile.wins = ((Number) m.getOrDefault("wins", 0)).intValue();
        profile.totalPnl = ((Number) m.getOrDefault("totalPnl", 0.0)).doubleValue();
        profile.confidenceScore = ((Number) m.getOrDefault("confidenceScore", 50.0)).doubleValue();
        profile.rsiEntryThreshold = ((Number) m.getOrDefault("rsiEntryThreshold", 30.0)).doubleValue();
        profile.atrStopMultiplier = ((Number) m.getOrDefault("atrStopMultiplier", 2.0)).doubleValue();
        profile.atrTakeProfitMultiplier = ((Number) m.getOrDefault("atrTakeProfitMultiplier", 2.5)).doubleValue();
        profile.maxVixForEntry = ((Number) m.getOrDefault("maxVixForEntry", 50.0)).doubleValue();
        profile.lastUpdated = ((Number) m.getOrDefault("lastUpdated", System.currentTimeMillis())).longValue();
        profile.updateCount = ((Number) m.getOrDefault("updateCount", 0)).intValue();

        // Load sets
        if (m.containsKey("enabledPatterns")) {
            ((List<String>) m.get("enabledPatterns")).forEach(profile.enabledPatterns::add);
        }
        if (m.containsKey("disabledPatterns")) {
            ((List<String>) m.get("disabledPatterns")).forEach(profile.disabledPatterns::add);
        }
        if (m.containsKey("lossCategories")) {
            Map<String, Integer> lossCats = (Map<String, Integer>) m.get("lossCategories");
            profile.lossCategories.putAll(lossCats);
        }

        return profile;
    }

    private void save() {
        try {
            if (!Files.exists(MEMORY_FILE.getParent())) {
                Files.createDirectories(MEMORY_FILE.getParent());
            }
            
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("memory", memory);
            data.put("profiles", new ArrayList<>(strategyProfiles.values()));
            
            mapper.writerWithDefaultPrettyPrinter().writeValue(MEMORY_FILE.toFile(), data);
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
        public final Map<String, Integer> lossCategories = new HashMap<>();

        TickerStats(String ticker) {
            this.ticker = ticker;
        }

        public void recordTrade(String strategy, boolean isWin, double pnl, double maxDD, double maxRunup) {
            totalTrades++;
            if (isWin) {
                wins++;
                currentStreak = currentStreak > 0 ? currentStreak + 1 : 1;
                bestStreak = Math.max(bestStreak, currentStreak);
                totalWinsPnl += pnl;
            } else {
                losses++;
                currentStreak = currentStreak < 0 ? currentStreak - 1 : -1;
                worstStreak = Math.min(worstStreak, currentStreak);
                totalLossesPnl += Math.abs(pnl);
            }
            totalPnl += pnl;
            maxDrawdown = Math.max(maxDrawdown, maxDD);
            lastTradeTime = ZonedDateTime.now();
            lastStrategy = strategy;
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
            return ticker + ": " + totalTrades + " trades, " + fmt1(getWinRate() * 100) + "% WR, PF=" + fmt2(getProfitFactor()) + ", PnL=$" + fmt2(totalPnl) + ", streak=" + currentStreak;
        }
    }
}
