package com.fgiaquinta.optionsquant.backtest.grid;

import com.fgiaquinta.optionsquant.domain.TimeFrame;

import java.time.LocalDate;
import java.util.List;

/**
 * Base backtest settings plus axes defining the parameter grid (v1: {@code tpMultiplierDelta} / {@code slMultiplierDelta} only).
 *
 * @param searchMode                {@code EXHAUSTIVE} (default) or {@code RANDOM}
 * @param randomSampleCount         required for {@code RANDOM}: number of cells to draw without replacement
 * @param randomSeed                optional seed for reproducible random sampling
 * @param constraintMinTrades       optional: cells with fewer trades excluded from "best" summary
 * @param constraintMaxDrawdownPct  optional: cells with higher max DD excluded (fraction, same as report)
 * @param primaryMetric             {@code TOTAL_PNL} (default), {@code PROFIT_FACTOR}, or {@code WIN_RATE}
 * @param walkForwardTrainDays      optional: when set with {@code walkForwardTestDays}, enables rolling walk-forward
 *                                  (in-sample grid then OOS single run per fold). Mutually exclusive with RANDOM mode in v1.
 * @param walkForwardTestDays       out-of-sample window length in calendar days (inclusive span)
 * @param walkForwardStepDays       calendar days to advance the train window start each fold; defaults to test length
 */
public record GridSearchRequest(
        List<String> tickers,
        LocalDate fromDate,
        LocalDate toDate,
        double initialCapital,
        double riskPerTradePct,
        double slippagePct,
        double commissionPerContract,
        int maxConcurrentTrades,
        TimeFrame executionTimeframe,
        boolean includeTradePlans,
        boolean deterministicMode,
        List<GridAxis> axes,
        String searchMode,
        Integer randomSampleCount,
        Long randomSeed,
        Integer constraintMinTrades,
        Double constraintMaxDrawdownPct,
        String primaryMetric,
        Integer walkForwardTrainDays,
        Integer walkForwardTestDays,
        Integer walkForwardStepDays
) {
}
