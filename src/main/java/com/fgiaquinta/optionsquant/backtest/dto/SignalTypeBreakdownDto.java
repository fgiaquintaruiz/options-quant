package com.fgiaquinta.optionsquant.backtest.dto;

/**
 * Per-signal-type aggregate metrics for a backtest run.
 */
public record SignalTypeBreakdownDto(
        String signalType,
        long trades,
        double winRate,
        double totalPnl
) {}
