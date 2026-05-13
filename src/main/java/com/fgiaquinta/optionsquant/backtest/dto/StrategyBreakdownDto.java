package com.fgiaquinta.optionsquant.backtest.dto;

/**
 * Per-strategy aggregate metrics for a backtest run.
 */
public record StrategyBreakdownDto(
        String strategy,
        long trades,
        double winRate,
        double totalPnl,
        double avgWin,
        double avgLoss
) {}
