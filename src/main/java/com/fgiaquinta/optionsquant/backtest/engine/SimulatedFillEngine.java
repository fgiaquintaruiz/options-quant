package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.FillResult;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;

import java.time.ZonedDateTime;

/**
 * Realistic fill model with slippage and commission.
 */
public class SimulatedFillEngine implements FillEngine {

    private final double slippagePct;
    private final double commissionPerContract;

    public SimulatedFillEngine(double slippagePct, double commissionPerContract) {
        this.slippagePct = slippagePct;
        this.commissionPerContract = commissionPerContract;
    }

    @Override
    public FillResult fillEntry(String ticker, String direction, int quantity, TradePlan plan, ZonedDateTime timestamp) {
        double basePrice = plan.entryPrice;
        double slippageAmount = basePrice * slippagePct;
        double fillPrice = basePrice + slippageAmount;
        double commission = commissionPerContract * quantity;
        return FillResult.success(fillPrice, slippageAmount, commission);
    }

    @Override
    public FillResult fillExit(String ticker, String direction, int quantity, double exitPrice, ZonedDateTime timestamp) {
        double slippageAmount = exitPrice * slippagePct;
        boolean isCall = direction.equalsIgnoreCase("CALL");
        double fillPrice = isCall ? exitPrice - slippageAmount : exitPrice + slippageAmount;
        double commission = commissionPerContract * quantity;
        return FillResult.success(fillPrice, slippageAmount, commission);
    }
}
