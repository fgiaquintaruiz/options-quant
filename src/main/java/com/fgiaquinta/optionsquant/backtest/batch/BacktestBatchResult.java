package com.fgiaquinta.optionsquant.backtest.batch;

import java.time.LocalDate;

/**
 * Aggregated backtest result for a single (ticker, strategy, timeframe) combination.
 */
public record BacktestBatchResult(
        String ticker,
        String strategy,
        String timeframe,
        int signalCount,
        int longSignals,
        int shortSignals,
        double winRate,
        LocalDate firstSignalDate,
        LocalDate lastSignalDate
) {}
