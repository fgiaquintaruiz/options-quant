package com.fgiaquinta.optionsquant.services;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.engine.AccountManager;
import com.fgiaquinta.optionsquant.engine.StrategyEngine;
import com.fgiaquinta.optionsquant.factories.ContractFactory;
import com.fgiaquinta.optionsquant.models.MarketRequest;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class IbkrService extends DefaultEWrapper {
    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AccountManager accountManager;

    private final AtomicInteger nextId = new AtomicInteger(1000);
    private final Map<Integer, MarketRequest> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, BarSeries> marketData = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();

    private StrategyEngine strategyEngine;
    private final CountDownLatch connectionLatch = new CountDownLatch(1);
    private CountDownLatch initializationLatch;
    private boolean accountSynced = false;

    // Formatter for fallback parsing if IBKR returns strings despite formatDate=2
    private static final DateTimeFormatter IB_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z");

    public IbkrService(AccountManager accountManager) {
        this.accountManager = accountManager;
        this.signal = new EJavaSignal();
        this.client = new EClientSocket(this, signal);
    }

    public void setStrategyEngine(StrategyEngine engine) { this.strategyEngine = engine; }

    public BarSeries getSeries(String ticker, TimeFrame tf) {
        String cacheKey = new MarketRequest(ticker, tf).getCacheKey();
        return marketData.get(cacheKey);
    }

    public void connect(String host, int port, int clientId) {
        System.out.println("🔌 Connecting to IBKR (" + host + ":" + port + ")...");
        client.eConnect(host, port, clientId);
        if (client.isConnected()) {
            final EReader reader = new EReader(client, signal);
            reader.start();
            new Thread(() -> {
                while (client.isConnected()) {
                    signal.waitForSignal();
                    try { reader.processMsgs(); } catch (Exception e) { e.printStackTrace(); }
                }
            }).start();
        }
    }

    public boolean awaitConnection(int seconds) throws InterruptedException {
        return connectionLatch.await(seconds, TimeUnit.SECONDS);
    }

    public void prepareInitialization(int tickerCount) {
        this.initializationLatch = new CountDownLatch(tickerCount + 1);
    }

    public void awaitInitialization(int seconds) throws InterruptedException {
        System.out.println("⏳ Awaiting synchronization with IBKR Gateway...");
        if (initializationLatch.await(seconds, TimeUnit.SECONDS)) {
            System.out.println("✅ Caches primed. System ready.");
        } else {
            System.err.println("⚠️ Sync timeout. Check connectivity and credentials.");
        }
    }

    public void requestInitialMetadata(List<String> tickers) {
        client.reqAccountUpdates(true, ConfigLoader.getConfig().ibkr.accountId);
        for (String ticker : tickers) {
            Contract contract = ContractFactory.createStockDefinition(ticker);
            client.reqContractDetails(nextId.getAndIncrement(), contract);
        }
    }

    /**
     * Fix: Added missing news subscription method.
     */
    public void subscribeToNewsProviders() {
        if (client.isConnected()) {
            client.reqNewsProviders();
        }
    }

    public void startMarketDataTracking(String ticker) {
        Contract contract = ContractFactory.createStockDefinition(ticker);
        for (TimeFrame tf : TimeFrame.values()) {
            MarketRequest request = new MarketRequest(ticker, tf);
            String cacheKey = request.getCacheKey();

            // Load what we have locally first
            BarSeries series = DataManager.loadSeries(cacheKey);
            marketData.put(cacheKey, series);

            int reqId = nextId.getAndIncrement();
            activeRequests.put(reqId, request);

            // FIX: Set the 9th parameter (keepUpToDate) to TRUE
            // This tells IBKR to keep sending us new bars as they close.
            client.reqHistoricalData(reqId, contract, "",
                    tf.getIbkrDuration(), tf.getIbkrBarSize(), "TRADES", 1, 2, true, null);
        }
    }

    /**
     * This method is called by IBKR whenever a LIVE bar is updated or closed.
     */
    @Override
    public void historicalDataUpdate(int reqId, com.ib.client.Bar bar) {
        MarketRequest request = activeRequests.get(reqId);
        if (request == null || strategyEngine == null) return;

        BarSeries series = marketData.get(request.getCacheKey());
        if (series == null) return;

        try {
            ZonedDateTime time = ZonedDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(bar.time())),
                    ZoneId.of("America/New_York")
            );

            // 1. Update the series
            if (series.getBarCount() == 0 || time.isAfter(series.getLastBar().getEndTime())) {
                series.addBar(time, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());

                // 2. TRIGGER: Tell the engine to evaluate the strategy immediately
                strategyEngine.onBarAdded(request.ticker(), request.tf(), series);

                System.out.println("📥 [Live] New " + request.tf() + " bar added for " + request.ticker() + " | Price: " + bar.close());
            }
        } catch (Exception e) {
            // Silently handle parsing if needed
        }
    }

    public String getOptimalExpiry(String ticker) { return tickerToBestExpiration.get(ticker); }

    @Override
    public void nextValidId(int orderId) {
        System.out.println("🆔 Handshake complete. Ready to send requests.");
        connectionLatch.countDown();
    }

    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        if ("NetLiquidation".equals(key) && "EUR".equals(currency)) {
            accountManager.updateBalance(Double.parseDouble(value));
            if (!accountSynced) {
                accountSynced = true;
                initializationLatch.countDown();
            }
        }
    }

    @Override
    public void contractDetails(int reqId, ContractDetails details) {
        client.reqSecDefOptParams(nextId.getAndIncrement(), details.contract().symbol(), "", "STK", details.contract().conid());
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
        String bestDate = expirations.stream().min(String::compareTo).orElse(null);
        if (bestDate != null) tickerToBestExpiration.put(tradingClass, bestDate);
    }

    @Override
    public void securityDefinitionOptionalParameterEnd(int reqId) {
        if (initializationLatch != null) initializationLatch.countDown();
    }

    @Override
    public void historicalData(int reqId, com.ib.client.Bar bar) {
        MarketRequest request = activeRequests.get(reqId);
        if (request == null) return;
        BarSeries series = marketData.get(request.getCacheKey());
        if (series == null) return;

        try {
            ZonedDateTime time;
            String timeStr = bar.time();

            // Robust parsing: check if it's a timestamp or a formatted string
            if (timeStr.matches("\\d+")) {
                time = ZonedDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(timeStr)), ZoneId.of("America/New_York"));
            } else {
                // IBKR sometimes returns strings for Daily bars even with formatDate=2
                time = ZonedDateTime.parse(timeStr.replace(" US/Eastern", " EST"), IB_DATE_FORMAT);
            }

            if (series.getBarCount() == 0 || time.isAfter(series.getLastBar().getEndTime())) {
                series.addBar(time, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());
            }
        } catch (Exception e) {
            System.err.println("❌ Error parsing bar time: " + bar.time());
        }
    }

    /**
     * Fix: Updated signature to 5 parameters to match your API version.
     */
    @Override
    public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (errorCode != 2104 && errorCode != 2106 && errorCode != 2158) {
            System.err.println("❌ [IBKR Error] " + errorCode + ": " + errorMsg);
            if (initializationLatch != null && id >= 1000) initializationLatch.countDown();
        }
    }

    public void placeOrder(String ticker, String action, int qty, double lmt, double tp, double sl, String strategy) {
        System.out.println("🚀 [IBKR] Placing " + action + " order for " + ticker + " (" + qty + " contracts)");
    }

    public void disconnect() { client.eDisconnect(); }
}