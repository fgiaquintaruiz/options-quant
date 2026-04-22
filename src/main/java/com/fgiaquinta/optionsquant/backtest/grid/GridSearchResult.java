package com.fgiaquinta.optionsquant.backtest.grid;

import java.util.List;

/**
 * Outcome of a grid search: one row per cell, optional CSV path on disk.
 * <p>
 * v1 API: synchronous {@code POST /backtest-ui/grid-search} — the HTTP request blocks until all cells finish
 * or {@link com.fgiaquinta.optionsquant.config.GridSearchProperties#getTimeoutMs()} stops the run early.
 *
 * @param walkForwardFolds    populated only for walk-forward runs; otherwise null
 * @param walkForwardSummary  aggregate OOS stats for walk-forward; otherwise null
 */
public record GridSearchResult(
        boolean success,
        String message,
        int requestedCells,
        int completedCells,
        List<GridCellResult> rows,
        String csvPath,
        GridOptimizationSummary optimization,
        List<WalkForwardFoldResult> walkForwardFolds,
        WalkForwardOosSummary walkForwardSummary
) {
}
