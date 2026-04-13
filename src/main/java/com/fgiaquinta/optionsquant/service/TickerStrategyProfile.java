package com.fgiaquinta.optionsquant.service;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stores per-ticker, per-strategy learned adjustments.
 *
 * Instead of blocking tickers, the system learns which setups work and adjusts:
 * - Which candlestick patterns to use/avoid
 * - Optimal RSI thresholds
 * - ATR stop multipliers
 * - Time-of-day filters
 * - VIX filters
 * - Confidence scoring
 *
 * Example:
 *   AMZN + c1squeezecall:
 *     - enabledPatterns: ["squeeze_breakout"] (NOT "engulfing")
 *     - rsiEntryThreshold: 25.0 (instead of 30)
 *     - atrStopMultiplier: 2.8 (wider stops needed)
 *     - earliestEntryTime: 10:00
 *     - maxVixForEntry: 22.0
 *     - confidenceScore: 0.22 (low, but not blocked)
 */
public class TickerStrategyProfile {
    public final String ticker;
    public final String strategy;
    
    // Pattern effectiveness tracking
    public final Set<String> enabledPatterns = new HashSet<>();    // Patterns that work well
    public final Set<String> disabledPatterns = new HashSet<>();   // Patterns to avoid
    public final Map<String, PatternStats> patternPerformance = new HashMap<>();
    
    // Strategy parameter adjustments
    public double rsiEntryThreshold = 30.0;        // Default RSI oversold threshold
    public double atrStopMultiplier = 2.0;         // ATR multiplier for stop loss
    public double atrTakeProfitMultiplier = 2.5;   // ATR multiplier for take profit
    
    // Time filters
    public LocalTime earliestEntryTime = LocalTime.MIN;  // No trades before this time
    public LocalTime latestEntryTime = LocalTime.MAX;    // No trades after this time
    
    // Market condition filters
    public double maxVixForEntry = 50.0;           // Skip if VIX above this
    public double minVixForEntry = 0.0;            // Skip if VIX below this
    
    // Market trend preference
    public String preferredMarketTrend = "any";    // "bullish", "bearish", "neutral", "any"
    
    // Confidence scoring (0-100%)
    public double confidenceScore = 50.0;          // Starts at 50%, adjusts with performance
    public int totalTrades = 0;
    public int wins = 0;
    public double totalPnl = 0.0;
    
    // Loss categorization
    public final Map<String, Integer> lossCategories = new HashMap<>();
    
    // Learning metadata
    public long lastUpdated = System.currentTimeMillis();
    public int updateCount = 0;

    public TickerStrategyProfile(String ticker, String strategy) {
        this.ticker = ticker;
        this.strategy = strategy;
        // By default, all patterns are enabled until we learn otherwise
        enabledPatterns.add("unknown");
    }

    /**
     * Records a trade outcome and updates the profile.
     */
    public void recordTrade(String pattern, boolean isWin, double pnl, String lossCategory,
                           int entryHour, double vix, String marketTrend) {
        totalTrades++;
        if (isWin) {
            wins++;
        }
        totalPnl += pnl;
        
        // Update pattern performance
        patternPerformance.computeIfAbsent(pattern, k -> new PatternStats(pattern));
        PatternStats ps = patternPerformance.get(pattern);
        ps.recordTrade(isWin, pnl);
        
        // Update enabled/disabled patterns based on performance
        if (ps.totalTrades >= 3) {
            if (ps.getWinRate() < 0.30) {
                disabledPatterns.add(pattern);
                enabledPatterns.remove(pattern);
            } else if (ps.getWinRate() > 0.60) {
                enabledPatterns.add(pattern);
                disabledPatterns.remove(pattern);
            }
        }
        
        // Update loss categorization
        if (!isWin && lossCategory != null) {
            lossCategories.merge(lossCategory, 1, Integer::sum);
        }
        
        // Recalculate confidence score
        recalculateConfidence();
        
        lastUpdated = System.currentTimeMillis();
        updateCount++;
    }

    /**
     * Recalculates confidence score based on performance metrics.
     */
    private void recalculateConfidence() {
        if (totalTrades < 3) {
            confidenceScore = 50.0; // Neutral until we have data
            return;
        }
        
        double winRate = (double) wins / totalTrades;
        double avgPnl = totalPnl / totalTrades;
        
        // Base score from win rate (0-60 points)
        confidenceScore = winRate * 60;
        
        // Add points for profitability (0-20 points)
        if (avgPnl > 0) {
            confidenceScore += Math.min(20, avgPnl / 10);
        }
        
        // Add points for consistency (0-20 points)
        if (totalTrades >= 10) {
            confidenceScore += 20;
        } else if (totalTrades >= 5) {
            confidenceScore += 10;
        }
        
        // Clamp to 0-100
        confidenceScore = Math.max(0, Math.min(100, confidenceScore));
    }

    /**
     * Returns the position size multiplier based on confidence.
     */
    public double getPositionSizeMultiplier() {
        if (totalTrades < 3) return 1.0;
        
        if (confidenceScore >= 80) return 2.0;
        if (confidenceScore >= 60) return 1.5;
        if (confidenceScore >= 40) return 1.0;
        if (confidenceScore >= 20) return 0.5;
        return 0.25;
    }

    /**
     * Returns the win rate.
     */
    public double getWinRate() {
        return totalTrades > 0 ? (double) wins / totalTrades : 0;
    }

    /**
     * Checks if a pattern should be used for this ticker+strategy.
     */
    public boolean isPatternAllowed(String pattern) {
        return !disabledPatterns.contains(pattern);
    }

    /**
     * Checks if current market conditions allow entry.
     */
    public boolean canEnter(LocalTime currentTime, double currentVix, String currentMarketTrend) {
        // Time filter
        if (currentTime.isBefore(earliestEntryTime) || currentTime.isAfter(latestEntryTime)) {
            return false;
        }
        
        // VIX filter
        if (currentVix > maxVixForEntry || currentVix < minVixForEntry) {
            return false;
        }
        
        // Market trend filter
        if (!"any".equals(preferredMarketTrend) && !preferredMarketTrend.equals(currentMarketTrend)) {
            return false;
        }
        
        return true;
    }

    /**
     * Gets the top loss category for analysis.
     */
    public String getTopLossCategory() {
        return lossCategories.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
    }

    @Override
    public String toString() {
        return String.format("%s+%s: %d trades, %.1f%% WR, $%.2f PnL, %.0f%% confidence",
                ticker, strategy, totalTrades, getWinRate() * 100, totalPnl, confidenceScore);
    }

    /**
     * Pattern-level performance statistics.
     */
    public static class PatternStats {
        public final String pattern;
        public int totalTrades = 0;
        public int wins = 0;
        public double totalPnl = 0.0;
        public double avgPnl = 0.0;

        public PatternStats(String pattern) {
            this.pattern = pattern;
        }

        public void recordTrade(boolean isWin, double pnl) {
            totalTrades++;
            if (isWin) wins++;
            totalPnl += pnl;
            avgPnl = totalTrades > 0 ? totalPnl / totalTrades : 0;
        }

        public double getWinRate() {
            return totalTrades > 0 ? (double) wins / totalTrades : 0;
        }
    }
}
