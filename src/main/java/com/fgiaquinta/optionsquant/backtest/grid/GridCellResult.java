package com.fgiaquinta.optionsquant.backtest.grid;

import java.util.Map;

/**
 * Metrics for one grid cell (one backtest run).
 */
public record GridCellResult(
        int cellIndex,
        Map<String, Double> parameters,
        double totalPnl,
        double totalReturnPct,
        int totalTrades,
        double winRate,
        double maxDrawdownPct,
        double profitFactor,
        long elapsedMs
) {
}
