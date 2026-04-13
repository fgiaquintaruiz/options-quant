package com.fgiaquinta.optionsquant.backtest.domain;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Records a complete trade (entry + exit) for reporting.
 * Enhanced with candlestick pattern tracking and market context for learning system.
 */
public record TradeRecord(
        String ticker,
        String strategy,
        String direction,
        int quantity,
        double entryPrice,
        ZonedDateTime entryTime,
        double exitPrice,
        ZonedDateTime exitTime,
        String exitReason,
        double grossPnl,
        double commission,
        double slippage,
        double netPnl,
        double maxDrawdown,
        double maxRunup,
        // === NEW FIELDS: Pattern & Context Tracking ===
        String candlestickPattern,              // e.g., "squeeze_breakout", "trend_continuation", "bounce"
        double atrAtEntry,                      // ATR value at entry time
        double vixAtEntry,                      // VIX level at entry (if available)
        int entryHour,                          // Hour of day (NY time) for time pattern analysis
        String marketTrend,                     // "bullish", "bearish", "neutral" (SPY trend)
        Map<String, Object> entryContext        // Additional context: RSI, MACD, volume, etc.
) {
        /**
         * Backward-compatible compact constructor for legacy code.
         */
        public TradeRecord(String ticker, String strategy, String direction, int quantity,
                          double entryPrice, ZonedDateTime entryTime, double exitPrice,
                          ZonedDateTime exitTime, String exitReason, double grossPnl,
                          double commission, double slippage, double netPnl,
                          double maxDrawdown, double maxRunup) {
                this(ticker, strategy, direction, quantity, entryPrice, entryTime, exitPrice,
                     exitTime, exitReason, grossPnl, commission, slippage, netPnl,
                     maxDrawdown, maxRunup, "unknown", 0.0, 0.0, 0, "neutral", new HashMap<>());
        }

        /**
         * Categorizes the loss reason for losing trades.
         * Returns null for winning trades.
         */
        public String categorizeLoss() {
                if (netPnl >= 0) return null;
                
                // Basic categorization based on exit reason and context
                if ("SL".equals(exitReason)) {
                        if (maxRunup > Math.abs(netPnl) * 0.5) {
                                return "TIMING"; // Was in profit, then reversed
                        }
                        if (atrAtEntry > 0 && maxDrawdown > atrAtEntry * 100 * 3) {
                                return "VOLATILITY"; // Stopped out by noise
                        }
                        return "MOMENTUM"; // Against the trend
                }
                
                if ("EOS".equals(exitReason)) {
                        return "TIMING"; // Held until end, time-based exit
                }
                
                return "STRATEGY_MISMATCH"; // TP hit but still lost (rare)
        }

        /**
         * Returns true if this trade was a winner.
         */
        public boolean isWin() {
                return netPnl > 0;
        }
}
