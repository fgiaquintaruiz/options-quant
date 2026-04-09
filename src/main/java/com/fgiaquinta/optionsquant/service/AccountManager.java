package com.fgiaquinta.optionsquant.service;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks IBKR account balance and calculates position sizing based on configurable risk %.
 * Uses IBKR's account updates stream to get real NetLiquidation value.
 */
@Slf4j
@Service
public class AccountManager {

    private final IbkrProperties ibkrProperties;
    private volatile double currentBalance = 0.0;
    private final AtomicInteger activeTrades = new AtomicInteger(0);

    private EClientSocket client;
    private EJavaSignal signal;

    public AccountManager(IbkrProperties ibkrProperties) {
        this.ibkrProperties = ibkrProperties;
    }

    /**
     * Connects to IBKR and subscribes to account updates.
     * This populates the balance from TWS.
     */
    public void connect() {
        if (client != null && client.isConnected()) {
            log.debug("AccountManager already connected");
            return;
        }

        log.info("Connecting AccountManager to IBKR at {}:{}", ibkrProperties.host(), ibkrProperties.port());
        this.signal = new EJavaSignal();

        EWrapper wrapper = new DefaultEWrapper() {
            @Override
            public void connectAck() {
                if (client.isAsyncEConnect()) client.startAPI();
            }

            @Override
            public void nextValidId(int orderId) {
                log.info("AccountManager connected, subscribing to account updates");
                client.reqAccountUpdates(true, "");
            }

            @Override
            public void updateAccountValue(String key, String val, String currency, String accountName) {
                if ("NetLiquidation".equals(key) && "USD".equals(currency)) {
                    try {
                        double balance = Double.parseDouble(val);
                        updateBalance(balance);
                    } catch (NumberFormatException e) {
                        log.warn("Failed to parse account balance: {}", val);
                    }
                }
            }

            @Override
            public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;
                log.debug("AccountManager IBKR error: code={}, msg={}", errorCode, errorMsg);
            }
        };

        this.client = new EClientSocket(wrapper, signal);
        client.eConnect(ibkrProperties.host(), ibkrProperties.port(), 999);

        if (!client.isConnected()) {
            log.warn("Failed to connect AccountManager to TWS (TWS may not be running)");
            return;
        }

        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) { /* ignore */ }
            }
        }, "account-manager-ereader").start();
    }

    /**
     * Updates the account balance from IBKR stream.
     */
    public void updateBalance(double balance) {
        this.currentBalance = balance;
        double riskPerTrade = balance * ibkrProperties.riskPerTradePct();
        log.info("💰 Account balance: $%.2f | risk limit (%.0f%%): $%.2f",
                balance, ibkrProperties.riskPerTradePct() * 100, riskPerTrade);
    }

    /**
     * Calculates the number of option contracts to buy based on configurable risk %.
     *
     * Formula: qty = (balance * riskPct) / (|entry - SL| * 100)
     *
     * @param entryPrice Entry price per contract
     * @param slPrice Stop loss price per contract
     * @return Number of contracts (0 if balance not available or risk too tight)
     */
    public int calculateQuantity(double entryPrice, double slPrice) {
        if (currentBalance <= 0) {
            log.warn("⚠️ Account balance is 0 or not synced yet.");
            return 0;
        }

        double riskPct = ibkrProperties.riskPerTradePct();
        double maxRiskDollars = currentBalance * riskPct;
        double riskPerContract = Math.abs(entryPrice - slPrice) * 100.0;

        if (riskPerContract == 0) {
            log.warn("Risk per contract is 0 (entry == SL)");
            return 0;
        }

        int qty = (int) Math.floor(maxRiskDollars / riskPerContract);

        if (qty < 1 && maxRiskDollars >= riskPerContract) {
            qty = 1;
        }

        log.info("📐 Position sizing: balance=$%.2f | risk=%.0f%%= $%.2f | risk/contract=$%.2f | qty=%d",
                currentBalance, riskPct * 100, maxRiskDollars, riskPerContract, qty);

        return qty;
    }

    public boolean canOpenNewTrade(int maxConcurrent) {
        if (maxConcurrent <= 0) return true;
        return activeTrades.get() < maxConcurrent;
    }

    public void addActiveTrade() { activeTrades.incrementAndGet(); }
    public void removeActiveTrade() { activeTrades.decrementAndGet(); }
    public int getActiveTradeCount() { return activeTrades.get(); }
    public double getCurrentBalance() { return currentBalance; }

    public void disconnect() {
        if (client != null && client.isConnected()) {
            client.reqAccountUpdates(false, "");
            client.eDisconnect();
            log.info("AccountManager disconnected");
        }
    }
}
