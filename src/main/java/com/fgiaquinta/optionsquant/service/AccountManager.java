package com.fgiaquinta.optionsquant.service;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.dto.PositionSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks IBKR account balance and calculates position sizing based on configurable risk %.
 * Uses IBKR's account updates stream to get real NetLiquidation value.
 * Includes position size caps and per-strategy risk tracking.
 */
@Slf4j
@Service
public class AccountManager {

    private final IbkrProperties ibkrProperties;
    private volatile double currentBalance = 0.0;
    private volatile String currentAccountId = null;
    private final AtomicInteger activeTrades = new AtomicInteger(0);

    // Position size safety cap
    private static final int MAX_CONTRACTS_PER_TRADE = 10;

    // Per-strategy performance tracking
    private final Map<String, StrategyStats> strategyStats = new ConcurrentHashMap<>();

    private final AtomicReference<EClientSocket> clientRef = new AtomicReference<>();
    private EJavaSignal signal;

    // ── Position snapshot (FR-1, NFR-2) ──────────────────────────────────────
    /** Live snapshot of account positions, keyed by ticker symbol. STK + OPT only. */
    private final ConcurrentHashMap<String, PositionSnapshot> positionsSnapshot = new ConcurrentHashMap<>();
    /** Instant of the last completed positionEnd() callback. Null until first snapshot. */
    private volatile Instant lastSnapshotAt = null;
    /** True once the first positionEnd() callback has fired (snapshot is ready to read). */
    private volatile boolean snapshotReady = false;

    public AccountManager(IbkrProperties ibkrProperties) {
        this.ibkrProperties = ibkrProperties;
    }

    /**
     * True if the account stream socket is connected to TWS/Gateway.
     */
    public boolean isConnected() {
        EClientSocket c = clientRef.get();
        return c != null && c.isConnected();
    }

    /**
     * Connects to IBKR and subscribes to account updates.
     * This populates the balance from TWS.
     */
    public void connect() {
        EClientSocket currentClient = clientRef.get();
        if (currentClient != null && currentClient.isConnected()) {
            log.debug("AccountManager already connected");
            return;
        }
        if (currentClient != null) {
            disconnect();
        }

        int clientId = ibkrProperties.accountManagerClientId();
        log.info("Connecting AccountManager to IBKR at {}:{} (clientId={})", ibkrProperties.host(), ibkrProperties.port(), clientId);
        this.signal = new EJavaSignal();

        EWrapper wrapper = new DefaultEWrapper() {
            @Override
            public void connectAck() {
                EClientSocket client = clientRef.get();
                if (client != null && client.isAsyncEConnect()) client.startAPI();
            }

            @Override
            public void nextValidId(int orderId) {
                log.info("AccountManager connected, subscribing to account updates and positions");
                EClientSocket client = clientRef.get();
                if (client != null) {
                    client.reqAccountUpdates(true, "");
                    // CRITICAL: reqPositions() MUST be called here (inside nextValidId),
                    // NOT directly in connect() — IBKR API requires nextValidId to fire first.
                    client.reqPositions();
                }
            }

            @Override
            public void position(String account, Contract contract, Decimal pos, double avgCost) {
                handlePosition(account, contract, pos.isValid() ? (int) pos.longValue() : 0, avgCost);
            }

            @Override
            public void positionEnd() {
                handlePositionEnd();
            }

            @Override
            public void updateAccountValue(String key, String val, String currency, String accountName) {
                if (currentAccountId == null) currentAccountId = accountName;
                // Accept NetLiquidation in any currency (BASE, USD, etc.)
                if ("NetLiquidation".equals(key) && val != null && !val.isEmpty()) {
                    try {
                        double balance = Double.parseDouble(val);
                        if (balance > 0) {
                            log.info("💰 Received balance update: ${} (currency: {}, account: {})",
                                    String.format("%,.2f", balance), currency, accountName);
                            updateBalance(balance);
                        }
                    } catch (NumberFormatException e) {
                        log.debug("Skipping non-numeric account value: {} = {} ({})", key, val, currency);
                    }
                }
            }

            @Override
            public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;
                log.debug("AccountManager IBKR error: code={}, msg={}", errorCode, errorMsg);
            }
        };

        EClientSocket newClient = new EClientSocket(wrapper, signal);
        clientRef.set(newClient);
        newClient.eConnect(ibkrProperties.host(), ibkrProperties.port(), clientId);

        if (!newClient.isConnected()) {
            log.warn("Failed to connect AccountManager to TWS (TWS may not be running)");
            return;
        }

        final EReader reader = new EReader(newClient, signal);
        reader.start();
        new Thread(() -> {
            EClientSocket client = clientRef.get();
            while (client != null && client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) { /* ignore */ }
                client = clientRef.get();
            }
        }, "account-manager-ereader").start();
    }

    /**
     * Updates the account balance from IBKR stream.
     */
    public void updateBalance(double balance) {
        this.currentBalance = balance;
        double riskPct = ibkrProperties.riskPerTradePct();
        double riskPerTrade = balance * riskPct;
        log.info("💰 Account balance: ${} | risk limit ({}%): ${}",
                String.format("%,.2f", balance), (int) (riskPct * 100), String.format("%,.2f", riskPerTrade));
    }

    /**
     * Calculates the number of option contracts to buy based on configurable risk %.
     *
     * Formula: qty = (balance * riskPct) / (|entry - SL| * 100)
     * Capped at MAX_CONTRACTS_PER_TRADE to prevent position sizing bugs.
     *
     * @param entryPrice Entry price per contract
     * @param slPrice Stop loss price per contract
     * @return Number of contracts (0 if balance not available or risk too tight)
     */
    public int calculateQuantity(double entryPrice, double slPrice) {
        return calculateQuantity(entryPrice, slPrice, "unknown");
    }

    /**
     * Calculates the number of option contracts with strategy tracking.
     *
     * @param entryPrice Entry price per contract
     * @param slPrice Stop loss price per contract
     * @param strategy Strategy name for tracking
     * @return Number of contracts (capped at MAX_CONTRACTS_PER_TRADE)
     */
    public int calculateQuantity(double entryPrice, double slPrice, String strategy) {
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

        // CRITICAL FIX: Cap position size to prevent bugs like P6 Reversal PUT (96 contracts)
        if (qty > MAX_CONTRACTS_PER_TRADE) {
            log.warn("🚨 Position size cap triggered: {} -> {} contracts (strategy: {})", 
                    qty, MAX_CONTRACTS_PER_TRADE, strategy);
            qty = MAX_CONTRACTS_PER_TRADE;
        }

        if (qty < 1 && maxRiskDollars >= riskPerContract) {
            qty = 1;
        }

        // Track strategy performance
        strategyStats.computeIfAbsent(strategy, k -> new StrategyStats()).recordPosition(qty);

        log.info("📐 Position sizing: balance=${} | risk={}%= ${} | risk/contract=${} | qty={} (capped at {}) | strategy={}",
                String.format("%,.2f", currentBalance), (int) (riskPct * 100), String.format("%,.2f", maxRiskDollars),
                String.format("%,.2f", riskPerContract), qty, MAX_CONTRACTS_PER_TRADE, strategy);

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
    public String getAccountId() { return currentAccountId; }

    // ── Position snapshot actions ─────────────────────────────────────────────

    /**
     * Sends a {@code reqPositions()} request to TWS to refresh the position snapshot.
     * No-op if the client is not connected (connection guard is at the caller, e.g.
     * {@link PositionPollingScheduler#pollPositions()}).
     */
    public void reqPositions() {
        EClientSocket client = clientRef.get();
        if (client != null && client.isConnected()) {
            client.reqPositions();
        }
    }

    // ── Position snapshot accessors ───────────────────────────────────────────

    /**
     * Returns a defensive copy of the current position snapshot map.
     * Thread-safe: caller receives an immutable view of the state at call time.
     */
    public Map<String, PositionSnapshot> getPositionsSnapshot() {
        return new HashMap<>(positionsSnapshot);
    }

    /** True once the first {@code positionEnd()} callback has fired. */
    public boolean isSnapshotReady() {
        return snapshotReady;
    }

    /** Instant of the last completed position snapshot. Null until first snapshot. */
    public Instant getLastSnapshotAt() {
        return lastSnapshotAt;
    }

    // ── Package-private callback handlers (also used as test hooks) ───────────

    /**
     * Processes a single position update from the EWrapper {@code position()} callback.
     * Filters out non-OPT secTypes silently — only options contracts are tracked.
     * Package-private to allow direct invocation in unit tests without a live TWS connection.
     */
    void handlePosition(String account, Contract contract, int quantity, double avgCost) {
        Types.SecType secTypeEnum = contract.secType();
        if (secTypeEnum != Types.SecType.OPT) {
            log.debug("Ignoring position for non-OPT contract: symbol={}, secType={}", contract.symbol(), secTypeEnum);
            return;
        }
        String symbol = contract.symbol();
        if (quantity <= 0) {
            final String secTypeStr = secTypeEnum.getApiString();
            positionsSnapshot.computeIfPresent(symbol, (k, existing) ->
                existing.secType().equals(secTypeStr) ? null : existing);
            return;
        }
        String secType = secTypeEnum.getApiString();
        PositionSnapshot snapshot = new PositionSnapshot(symbol, secType, contract, quantity, avgCost, Instant.now());
        positionsSnapshot.put(symbol, snapshot);
        log.debug("Position snapshot updated: symbol={}, secType={}, qty={}, avgCost={}", symbol, secType, quantity, avgCost);
    }

    /**
     * Marks the snapshot batch as complete when the EWrapper {@code positionEnd()} callback fires.
     * Sets {@code snapshotReady = true} and records the timestamp.
     * Package-private to allow direct invocation in unit tests.
     */
    void handlePositionEnd() {
        lastSnapshotAt = Instant.now();
        snapshotReady = true;
        log.debug("Position snapshot complete: {} positions loaded at {}", positionsSnapshot.size(), lastSnapshotAt);
    }

    /**
     * Simulates the {@code nextValidId()} callback logic for testing purposes.
     * Calls {@code reqAccountUpdates} and {@code reqPositions} on the given client.
     * Package-private — test hook only; production code uses the anonymous EWrapper.
     */
    void handleNextValidId(EClientSocket client) {
        if (client != null) {
            client.reqAccountUpdates(true, "");
            client.reqPositions();
        }
    }

    /**
     * Injects a mock {@link EClientSocket} into the {@code clientRef} for unit testing.
     * Package-private — test hook only; never called in production.
     */
    void injectClientForTest(EClientSocket client) {
        clientRef.set(client);
    }

    @jakarta.annotation.PreDestroy
    public void disconnect() {
        EClientSocket client = clientRef.getAndSet(null);
        if (client != null && client.isConnected()) {
            client.reqAccountUpdates(false, "");
            client.eDisconnect();
            log.info("AccountManager disconnected");
        }
    }

    /**
     * Records trade result for strategy performance tracking.
     */
    public void recordTradeResult(String strategy, double pnl) {
        strategyStats.computeIfAbsent(strategy, k -> new StrategyStats()).recordTrade(pnl);
    }

    /**
     * Gets strategy performance stats.
     */
    public StrategyStats getStrategyStats(String strategy) {
        return strategyStats.get(strategy);
    }

    /**
     * Gets all strategy performance stats.
     */
    public Map<String, StrategyStats> getAllStrategyStats() {
        return Map.copyOf(strategyStats);
    }

    /**
     * Checks if strategy should be disabled based on performance.
     * Returns true if win rate < 40% over last 20 trades.
     */
    public boolean isStrategyUnderperforming(String strategy) {
        StrategyStats stats = strategyStats.get(strategy);
        if (stats == null || stats.totalTrades < 20) return false;
        return stats.getWinRate() < 0.40;
    }

    /**
     * Inner class to track per-strategy performance.
     */
    public static class StrategyStats {
        int totalTrades = 0;
        int winningTrades = 0;
        double totalPnl = 0;
        int maxPositionSize = 0;

        public void recordPosition(int qty) {
            if (qty > maxPositionSize) maxPositionSize = qty;
        }

        public void recordTrade(double pnl) {
            totalTrades++;
            totalPnl += pnl;
            if (pnl > 0) winningTrades++;
        }

        public double getWinRate() {
            return totalTrades > 0 ? (double) winningTrades / totalTrades : 0;
        }

        public int getTotalTrades() { return totalTrades; }
        public int getWinningTrades() { return winningTrades; }
        public double getTotalPnl() { return totalPnl; }
        public int getMaxPositionSize() { return maxPositionSize; }
    }
}
