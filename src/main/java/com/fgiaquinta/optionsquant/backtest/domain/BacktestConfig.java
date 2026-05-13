package com.fgiaquinta.optionsquant.backtest.domain;

import com.fgiaquinta.optionsquant.domain.TimeFrame;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Configuration for a single backtest run.
 */
public record BacktestConfig(
        List<String> tickers,
        LocalDate fromDate,
        LocalDate toDate,
        double initialCapital,
        double riskPerTradePct,
        double slippagePct,
        double commissionPerContract,
        int maxConcurrentTrades,
        TimeFrame executionTimeframe,
        boolean includeTradePlans,
        boolean deterministicMode,
        // Added to TP/SL ATR multipliers for this run only (e.g. retest trial); applied in RiskCalculator across all workers.
        double tpMultiplierDelta,
        double slMultiplierDelta,
        /**
         * Optional callback invoked by BacktestEngine after each ticker completes.
         * Called from worker threads — implementation must be thread-safe.
         * {@code null} means no callback (backward-compatible default).
         */
        BiConsumer<String, List<TradeRecord>> onTickerComplete,
        /**
         * Earliest ET time at which a new entry signal is allowed (inclusive).
         * Default: 9:45 AM ET (the course author's method — after the first 15-minute candle closes).
         */
        LocalTime entryWindowStart,
        /**
         * Latest ET time at which a new entry signal is allowed (exclusive).
         * Default: 10:30 AM ET (the course author's method — 45-minute opening window).
         */
        LocalTime entryWindowEnd,
        /**
         * ET time at which all open positions are force-closed.
         * Default: 1:00 PM ET (the course author's method — avoid afternoon chop and IV collapse).
         */
        LocalTime forcedCloseTime
) {
    public BacktestConfig {
        if (initialCapital <= 0) throw new IllegalArgumentException("Initial capital must be positive");
        if (riskPerTradePct <= 0 || riskPerTradePct > 1) throw new IllegalArgumentException("Risk must be between 0 and 1");
        if (slippagePct < 0) throw new IllegalArgumentException("Slippage cannot be negative");
        if (commissionPerContract < 0) throw new IllegalArgumentException("Commission cannot be negative");
        if (maxConcurrentTrades < 0) throw new IllegalArgumentException("Max concurrent trades cannot be negative");
        if (tpMultiplierDelta < -10 || tpMultiplierDelta > 10) throw new IllegalArgumentException("tpMultiplierDelta out of range");
        if (slMultiplierDelta < -10 || slMultiplierDelta > 10) throw new IllegalArgumentException("slMultiplierDelta out of range");
        if (entryWindowStart == null) entryWindowStart = LocalTime.of(9, 45);
        if (entryWindowEnd == null) entryWindowEnd = LocalTime.of(10, 30);
        if (forcedCloseTime == null) forcedCloseTime = LocalTime.of(13, 0);
        if (!entryWindowStart.isBefore(entryWindowEnd))
            throw new IllegalArgumentException("entryWindowStart must be before entryWindowEnd");
    }

    /**
     * Default config for quick backtests. No per-ticker callback (backward compatible).
     * Entry window: 9:45–10:30 AM ET. Forced close: 1:00 PM ET.
     */
    public static BacktestConfig defaults(List<String> tickers, LocalDate from, LocalDate to) {
        return new BacktestConfig(
                tickers, from, to,
                50000.0,    // $50k initial capital
                0.02,       // 2% risk
                0.0008,     // 0.08% on underlying ≈ 2% on option contract (delta 0.60)
                0.65,       // $0.65 per contract (typical IBKR options commission)
                3,          // max 3 concurrent trades
                TimeFrame.MIN_15, // evaluate strategies every 15m candle
                true,       // include trade plans
                false,      // deterministic mode off by default
                0.0,
                0.0,
                null,       // no callback
                LocalTime.of(9, 45),   // entryWindowStart
                LocalTime.of(10, 30),  // entryWindowEnd
                LocalTime.of(13, 0)    // forcedCloseTime
        );
    }

    /**
     * Config with a per-ticker completion callback. All other values match {@link #defaults}.
     * The callback is invoked by BacktestEngine from worker threads after each ticker finishes.
     *
     * @param callback {@code (ticker, trades) -> ...} — must be thread-safe
     */
    public static BacktestConfig withCallback(
            List<String> tickers, LocalDate from, LocalDate to,
            BiConsumer<String, List<TradeRecord>> callback) {
        return new BacktestConfig(
                tickers, from, to,
                50000.0,
                0.02,
                0.0008,     // 0.08% on underlying ≈ 2% on option contract (delta 0.60)
                0.65,
                3,
                TimeFrame.MIN_15,
                true,
                false,
                0.0,
                0.0,
                callback,
                LocalTime.of(9, 45),   // entryWindowStart
                LocalTime.of(10, 30),  // entryWindowEnd
                LocalTime.of(13, 0)    // forcedCloseTime
        );
    }
}
