package com.fgiaquinta.optionsquant.backtest.dto;

/**
 * Summary of a single backtest run — aggregated from backtest_progress.
 */
public record RunSummaryDto(
        String runId,
        int totalTickers,
        int completedTickers,
        long totalTrades,
        double totalPnl
) {}
