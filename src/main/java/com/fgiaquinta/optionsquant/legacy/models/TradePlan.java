package com.fgiaquinta.optionsquant.models;

import java.time.LocalTime;

public class TradePlan {
    public final double entryPrice;
    public final double takeProfit;
    public final double stopLoss;
    public final boolean isCall;
    public final LocalTime intradayExitTime;

    public TradePlan(double entryPrice, double takeProfit, double stopLoss, boolean isCall, LocalTime intradayExitTime) {
        this.entryPrice = entryPrice;
        this.takeProfit = takeProfit;
        this.stopLoss = stopLoss;
        this.isCall = isCall;
        this.intradayExitTime = intradayExitTime;
    }
}