package com.fgiaquinta.optionsquant.backtest.domain;

import com.fgiaquinta.optionsquant.domain.TimeFrame;

import java.time.LocalDate;
import java.util.List;

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
        boolean deterministicMode
) {
    public BacktestConfig {
        if (initialCapital <= 0) throw new IllegalArgumentException("Initial capital must be positive");
        if (riskPerTradePct <= 0 || riskPerTradePct > 1) throw new IllegalArgumentException("Risk must be between 0 and 1");
        if (slippagePct < 0) throw new IllegalArgumentException("Slippage cannot be negative");
        if (commissionPerContract < 0) throw new IllegalArgumentException("Commission cannot be negative");
        if (maxConcurrentTrades < 0) throw new IllegalArgumentException("Max concurrent trades cannot be negative");
    }

    /**
     * Default config for quick backtests.
     */
    public static BacktestConfig defaults(List<String> tickers, LocalDate from, LocalDate to) {
        return new BacktestConfig(
                tickers, from, to,
                50000.0,    // $50k initial capital
                0.02,       // 2% risk
                0.005,      // 0.5% slippage
                0.65,       // $0.65 per contract (typical IBKR options commission)
                3,          // max 3 concurrent trades
                TimeFrame.MIN_15, // evaluate strategies every 15m candle
                true,       // include trade plans
                false       // deterministic mode off by default
        );
    }
}
