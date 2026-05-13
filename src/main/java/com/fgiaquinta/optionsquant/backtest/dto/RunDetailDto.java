package com.fgiaquinta.optionsquant.backtest.dto;

import java.util.List;

/**
 * Detailed statistics for a single backtest run, including per-strategy and per-signal-type breakdowns.
 */
public record RunDetailDto(
        String runId,
        long totalTrades,
        double totalPnl,
        double winRate,
        List<StrategyBreakdownDto> byStrategy,
        List<SignalTypeBreakdownDto> bySignalType
) {}
