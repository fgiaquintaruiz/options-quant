package com.fgiaquinta.optionsquant.backtest.grid;

/**
 * Promote grid-search deltas to persisted ticker memory as absolute ATR multipliers
 * (baseline from {@link com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator} maps + deltas).
 */
public record PromoteRiskRequest(
        String ticker,
        String strategyName,
        boolean isCall,
        double tpMultiplierDelta,
        double slMultiplierDelta,
        boolean dryRun
) {
}
