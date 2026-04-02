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
    /**
     * Updates the account equity and prints the risk status.
     * @param balance The total net liquidation value in the base currency.
     */
    // Notice the new 'incomingAccountId' parameter
    public void updateBalance(String incomingAccountId, double balance) {
        // If an account ID is configured, ignore updates from other accounts
        if (this.accountId != null && !this.accountId.equals(incomingAccountId)) {
            return;
        }

        this.currentBalance = balance;

        // Calculate the risk limit based on the new balance
        double riskPerTrade = balance * ConfigLoader.getConfig().getDouble("risk", "riskPerTradePct");

        // English log for balance synchronization
        System.out.printf("💰 [Account] Equity Synced: %.2f EUR | Strategy Risk Limit: %.2f EUR%n",
                balance, riskPerTrade);
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
        int maxTrades = (int) ConfigLoader.getConfig().getDouble("risk", "maxConcurrentTrades");
        return activeTrades.get() < maxTrades;
    }

    // Calculates exactly how many contracts you can afford while respecting the 2% rule
    public int calculateQuantity(double entryPrice, double slPrice) {
        if (currentBalance <= 0) return 0;

        double riskPct = ConfigLoader.getConfig().getDouble("risk", "riskPerTradePct");
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