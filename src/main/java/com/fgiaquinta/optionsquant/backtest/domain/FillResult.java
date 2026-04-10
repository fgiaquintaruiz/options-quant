package com.fgiaquinta.optionsquant.backtest.domain;

/**
 * Result of simulating a fill for a trade.
 */
public record FillResult(
        boolean filled,
        double fillPrice,
        double slippage,
        double commission,
        double netProceeds,
        String reason
) {
    public static FillResult success(double fillPrice, double slippage, double commission) {
        return new FillResult(true, fillPrice, slippage, commission,
                fillPrice - commission, "filled");
    }

    public static FillResult rejected(String reason) {
        return new FillResult(false, 0, 0, 0, 0, reason);
    }
}
