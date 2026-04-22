package com.fgiaquinta.optionsquant.backtest.grid;

/**
 * Result of {@link PromoteRiskService#promote(PromoteRiskRequest)}.
 */
public record PromoteResult(
        boolean persisted,
        String message,
        double appliedSlAtrMult,
        double appliedTpAtrMult
) {
}
