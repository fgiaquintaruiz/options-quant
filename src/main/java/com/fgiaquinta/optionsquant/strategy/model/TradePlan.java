package com.fgiaquinta.optionsquant.strategy.model;

import java.time.LocalTime;

/**
 * Represents a complete trade plan with entry, take profit, and stop loss.
 */
public class TradePlan {
    public final double entryPrice;
    public final double takeProfit;
    public final double stopLoss;
    public final boolean isCall;
    public final LocalTime intradayExitTime;
    public final double atr;  // ATR at entry time (for position sizing and analysis)
    
    // Alias for clarity in learning system
    public double getAtrAtEntry() {
        return atr;
    }

    public TradePlan(double entryPrice, double takeProfit, double stopLoss, boolean isCall, LocalTime intradayExitTime) {
        this(entryPrice, takeProfit, stopLoss, isCall, intradayExitTime, 0);
    }

    public TradePlan(double entryPrice, double takeProfit, double stopLoss, boolean isCall, LocalTime intradayExitTime, double atr) {
        this.entryPrice = entryPrice;
        this.takeProfit = takeProfit;
        this.stopLoss = stopLoss;
        this.isCall = isCall;
        this.intradayExitTime = intradayExitTime;
        this.atr = atr;
    }
}
