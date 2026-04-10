package com.fgiaquinta.optionsquant.backtest.domain;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Complete backtest results with aggregate and per-strategy breakdown.
 */
public record BacktestReport(
        double initialCapital,
        double finalCapital,
        double totalReturn,
        double totalReturnPct,
        int totalTrades,
        int winningTrades,
        int losingTrades,
        double winRate,
        double profitFactor,
        double maxDrawdown,
        double maxDrawdownPct,
        double sharpeRatio,
        double avgWin,
        double avgLoss,
        double avgTradeDurationHours,
        Map<String, StrategyStats> byStrategy,
        Map<String, StrategyStats> byTicker,
        List<EquityPoint> equityCurve,
        List<TradeRecord> trades,
        long elapsedMs
) {
    public record StrategyStats(
            int trades,
            int wins,
            double winRate,
            double totalPnl,
            double profitFactor,
            double maxDrawdown
    ) {}

    public record EquityPoint(
            ZonedDateTime timestamp,
            double equity
    ) {}
}
