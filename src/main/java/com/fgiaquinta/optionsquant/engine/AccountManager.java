package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import java.util.concurrent.atomic.AtomicInteger;

public class AccountManager {
    private double currentBalance = 0.0;
    private final AtomicInteger activeTrades = new AtomicInteger(0);
    private String accountId;

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    // Updates balance directly from IBKR's NetLiquidation stream
    public void updateBalance(String key, String value, String accountName) {
        if ("NetLiquidation".equals(key) && accountName.equals(this.accountId)) {
            this.currentBalance = Double.parseDouble(value);
        }
    }

    public void addActiveTrade() {
        activeTrades.incrementAndGet();
    }

    public void removeActiveTrade() {
        if (activeTrades.get() > 0) {
            activeTrades.decrementAndGet();
        }
    }

    public boolean canOpenNewTrade() {
        int maxTrades = ConfigLoader.getConfig().risk.maxConcurrentTrades;
        return activeTrades.get() < maxTrades;
    }

    // Calculates exactly how many contracts you can afford while respecting the 2% rule
    public int calculateQuantity(double entryPrice, double slPrice) {
        if (currentBalance <= 0) return 0;

        double riskPct = ConfigLoader.getConfig().risk.riskPerTradePct;
        double maxRiskDollars = currentBalance * riskPct;

        // Options multiplier is 100
        double riskPerContract = Math.abs(entryPrice - slPrice) * 100.0;

        if (riskPerContract == 0) return 0;

        return (int) Math.floor(maxRiskDollars / riskPerContract);
    }

    public double getCurrentBalance() {
        return currentBalance;
    }
}