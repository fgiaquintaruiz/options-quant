package com.fgiaquinta.optionsquant.backtest.grid;

/**
 * Aggregated out-of-sample metrics across walk-forward folds that actually ran an OOS backtest.
 */
public record WalkForwardOosSummary(
        int foldsPlanned,
        int foldsCompleted,
        int foldsWithOosBacktest,
        double aggregateOosPnl,
        int aggregateOosTrades,
        String primaryMetricUsed,
        String detail
) {
}
