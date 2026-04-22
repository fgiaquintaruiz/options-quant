package com.fgiaquinta.optionsquant.backtest.grid;

import java.time.LocalDate;
import java.util.Map;

/**
 * One rolling walk-forward step: grid on the in-sample window, optional out-of-sample backtest
 * with the best in-sample cell's TP/SL deltas.
 *
 * @param isOptimizationWinner true when the in-sample grid produced an admissible optimization winner
 * @param bestIsParameters     best TP/SL deltas from in-sample grid (empty if no winner)
 * @param isBestMetricValue    primary metric value of the winning in-sample cell, if any
 * @param isGridMessage        in-sample grid status (e.g. ok, timeout)
 * @param skippedReason        non-null when OOS was skipped (no IS winner, IS grid incomplete, or deadline)
 */
public record WalkForwardFoldResult(
        int foldIndex,
        LocalDate trainFrom,
        LocalDate trainTo,
        LocalDate testFrom,
        LocalDate testTo,
        boolean isOptimizationWinner,
        Map<String, Double> bestIsParameters,
        Double isBestMetricValue,
        String isGridMessage,
        int isCompletedCells,
        int isRequestedCells,
        double oosTotalPnl,
        int oosTotalTrades,
        double oosWinRate,
        double oosMaxDrawdownPct,
        double oosProfitFactor,
        long oosElapsedMs,
        String skippedReason
) {
}
