package com.fgiaquinta.optionsquant.services;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.engine.AccountManager;
import com.fgiaquinta.optionsquant.engine.StrategyEngine;
import com.fgiaquinta.optionsquant.engine.TradeManager;
import com.fgiaquinta.optionsquant.factories.ContractFactory;
import com.fgiaquinta.optionsquant.models.MarketRequest;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class IbkrService extends DefaultEWrapper {

    private final AtomicInteger nextId = new AtomicInteger(1000);
    private final Map<Integer, MarketRequest> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, BarSeries> marketData = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();
    private final java.util.Set<Integer> pendingBackfills = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> tickerToConId = new ConcurrentHashMap<>();
    private final Map<Integer, String> orderIdToTicker = new ConcurrentHashMap<>();
    private final CountDownLatch initializationLatch = new CountDownLatch(1);
    private boolean accountSynced = false;
    private final Map<String, Set<Double>> tickerToValidStrikes = new ConcurrentHashMap<>();
    private static final DateTimeFormatter IB_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z");
    // Maps Request ID -> Description (e.g., "Metadata: AAPL" or "Options: TSLA")
    private final Map<Integer, String> requestTracker = new ConcurrentHashMap<>();

    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AccountManager accountManager;
    private StrategyEngine strategyEngine;
    private TradeManager tradeManager;
    private com.fgiaquinta.optionsquant.engine.MarketRadar marketRadar;

    public IbkrService(AccountManager accountManager) {
        this.accountManager = accountManager;
        this.signal = new EJavaSignal();
        this.client = new EClientSocket(this, signal);
    }

    public void setStrategyEngine(StrategyEngine engine) { this.strategyEngine = engine; }

    public void setTradeManager(TradeManager tradeManager) {
        this.tradeManager = tradeManager;
    }

    public void setMarketRadar(com.fgiaquinta.optionsquant.engine.MarketRadar radar) {
        this.marketRadar = radar;
    }

    @Override
    public void connectAck() {
        if (client.isAsyncEConnect()) {
            System.out.println("🤝 [IBKR] Connection Acknowledged.");
            client.startAPI();
        }
    }

    @Override
    public void nextValidId(int orderId) {
        // This is the signal that the connection is 100% ready to send requests
        System.out.println("✅ [IBKR] System Ready. Next Valid Order ID: " + orderId);
        nextId.set(orderId);
        initializationLatch.countDown(); // 👈 This releases the "Wait"
    }

    public boolean waitForConnection(int timeoutSeconds) {
        try {
            return initializationLatch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }
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

    public void requestInitialMetadata(List<String> tickers) {
        client.reqAccountUpdates(true, ConfigLoader.getConfig().getString("ibkr", "accountId"));
        for (String ticker : tickers) {
            int id = nextId.getAndIncrement();
            requestTracker.put(id, "Metadata: " + ticker); // 👈 Track it
            Contract contract = ContractFactory.createStockDefinition(ticker);
            System.out.println("🔍 [Metadata Request] ID: " + id + " | Ticker: [" + contract.symbol() + "] | Exch: " + contract.exchange());
            client.reqContractDetails(id, contract);
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
        System.out.println("📡 Requesting live data for: " + ticker);

        for (TimeFrame tf : TimeFrame.values()) {
            MarketRequest request = new MarketRequest(ticker, tf);
            String cacheKey = request.getCacheKey();

            // Load what we have locally first
            BarSeries series = DataManager.loadSeries(cacheKey);
            marketData.put(cacheKey, series);

            int reqId = nextId.getAndIncrement();
            // 👉 ADD THIS LINE TO FIX (Unknown Request)
            requestTracker.put(reqId, "Live-Data [" + tf + "]: " + ticker);
            activeRequests.put(reqId, request);
            // ADD THIS: Register the request as pending
            pendingBackfills.add(reqId);

            System.out.println("   -> Subscribing to " + tf + " (ID: " + reqId + ")");

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
        // Log every single entry attempt
        System.out.println("🔎 [IbkrService] historicalDataUpdate triggered for reqId: " + reqId);

        MarketRequest request = activeRequests.get(reqId);
        if (request == null) {
            System.out.println("⚠️ [IbkrService] Ignored: No active request found for reqId " + reqId);
            return;
        }

        if (strategyEngine == null) {
            System.out.println("⚠️ [IbkrService] Ignored: StrategyEngine is null.");
            return;
        }

        BarSeries series = marketData.get(request.getCacheKey());
        if (series == null) {
            System.out.println("⚠️ [IbkrService] Ignored: BarSeries is null for cacheKey " + request.getCacheKey());
            return;
        }

        try {
            long timestamp;
            try {
                timestamp = Long.parseLong(bar.time());
            } catch (NumberFormatException e) {
                System.out.println("⚠️ [IbkrService] Ignored: bar.time() is not a valid timestamp string: " + bar.time());
                return;
            }

            ZonedDateTime time = ZonedDateTime.ofInstant(
                    Instant.ofEpochSecond(timestamp),
                    ZoneId.of("America/New_York")
            );

            System.out.println("📊 [IbkrService] Parsed bar time: " + time + " for " + request.ticker() + " [" + request.timeFrame() + "]");

            // Update the series only if new
            if (series.getBarCount() == 0 || time.isAfter(series.getLastBar().getEndTime())) {
                System.out.println("✅ [IbkrService] Adding new bar to series for " + request.ticker());
                series.addBar(time, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());

                System.out.println("🚀 [IbkrService] Calling StrategyEngine.onBarAdded...");
                strategyEngine.onBarAdded(request.ticker(), request.timeFrame(), series);
            } else {
                System.out.println("⏭️ [IbkrService] Skipped: Bar is older or equal to last bar. Current bar: " + time + " | Last bar: " + series.getLastBar().getEndTime());
            }
        } catch (Exception e) {
            System.err.println("❌ [IbkrService] Fatal error processing bar for " + request.ticker() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public String getOptimalExpiry(String ticker) { return tickerToBestExpiration.get(ticker); }

    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        if ("NetLiquidation".equals(key) && "EUR".equals(currency)) {
            accountManager.updateBalance(accountName, Double.parseDouble(value));
            if (!accountSynced) {
                accountSynced = true;
                initializationLatch.countDown();
            }
        }
    }

    @Override
    public void contractDetails(int reqId, ContractDetails details) {
        String ticker = details.contract().symbol();
        int conId = details.contract().conid();
        tickerToConId.put(ticker, conId);

        int optId = nextId.getAndIncrement();
        requestTracker.put(optId, "OptionParams: " + ticker); // 👈 Track it
        client.reqSecDefOptParams(optId, ticker, "", "STK", conId);
    }

    @Override
    public void orderStatus(int orderId, String status, com.ib.client.Decimal var3, com.ib.client.Decimal var4,
                            double var5, long var7, int parentId, double lastFillPrice, int var12, String var13, double var14) {
        if ("Filled".equalsIgnoreCase(status) && parentId != 0) {
            String ticker = orderIdToTicker.get(orderId);

            if (ticker != null) {
                System.out.println("📉 [IbkrService] Exit filled for " + ticker + " at $" + lastFillPrice);

                // 👉 Notify TradeManager to update the Staircase Filter
                tradeManager.recordExit(ticker, lastFillPrice);

                // Clean up memory
                orderIdToTicker.values().removeIf(val -> val.equals(ticker));            }
        }
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass,
                                                    String multiplier, Set<String> expirations, Set<Double> strikes) {
        if (!"SMART".equals(exchange)) return;

        String ticker = tickerToConId.entrySet().stream()
                .filter(e -> e.getValue() == underlyingConId)
                .map(Map.Entry::getKey).findFirst().orElse(null);

        if (ticker != null && expirations != null && !expirations.isEmpty()) {
            // Logic: Find the closest Friday that is at least 48 hours away
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
            String minAllowedDate = LocalDate.now().plusDays(2).format(fmt);

            List<String> validExps = expirations.stream()
                    .filter(exp -> exp.compareTo(minAllowedDate) >= 0)
                    .sorted()
                    .collect(Collectors.toList());

            if (!validExps.isEmpty()) {
                tickerToBestExpiration.put(ticker, validExps.get(0));
                tickerToValidStrikes.put(ticker, strikes); // Store valid strikes to fix Error 200
                System.out.println("📅 [IBKR] Set 48h+ Expiry for " + ticker + ": " + validExps.get(0));
            }
        }
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
//                System.out.println("⏳ [BACKFILL] Loading historical bar for " + request.ticker() + " [" + request.timeFrame() + "] @ " + bar.close());
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
        // Look up the ticker/context from the ID we saved
        String context = requestTracker.getOrDefault(id, "Unknown Request");

        // Filter out the "OK" messages
        if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;

        System.err.println("❌ [IBKR Error]");
        System.err.println("   ID:       " + id);
        System.err.println("   Context:  " + context); // This tells you: "Order: TSLA"
        System.err.println("   Code:     " + errorCode);
        System.err.println("   Message:  " + errorMsg);
        System.err.println("--------------------------------------------------");
    }

    public void placeOrder(String ticker, String side, int qty, double entry, double tp, double sl, String strategy) {
        System.out.println("🚀 [IBKR] Placing " + side + " order for " + ticker + " | Qty: " + qty + " | Strategy: " + strategy);

        // 1. Get the dynamic expiration
        String expiration = getOptimalExpiry(ticker);
        if (expiration == null) {
            System.err.println("❌ Aborting: No expiration found for " + ticker);
            return;
        }

        // 2. Get the Underlying Contract ID
        Integer underlyingConId = tickerToConId.get(ticker);
        if (underlyingConId == null) {
            System.err.println("❌ Aborting: Underlying Contract ID not found for " + ticker);
            return;
        }

        // 3. FIND THE REAL STRIKE (Fix for Error 200)
        Set<Double> strikes = tickerToValidStrikes.get(ticker);
        if (strikes == null || strikes.isEmpty()) {
            System.err.println("❌ Aborting: No valid strikes loaded for " + ticker + ". Check metadata loading.");
            return;
        }

        // Use Java Streams to find the strike with the smallest difference from the entry price
        double bestStrike = strikes.stream()
                .min(Comparator.comparingDouble(s -> Math.abs(s - entry)))
                .orElse((double) Math.round(entry)); // Fallback to rounding if the set is somehow empty

        String right = side.equalsIgnoreCase("CALL") ? "C" : "P";

        // 4. Build the Option Contract with the VALIDated strike
        com.ib.client.Contract contract = com.fgiaquinta.optionsquant.factories.ContractFactory.createOptionContract(ticker, expiration, bestStrike, right);

        // 5. Generate 3 unique IDs for the Bracket
        int pId = nextId.getAndIncrement();
        int tpId = nextId.getAndIncrement();
        int slId = nextId.getAndIncrement();

        requestTracker.put(pId, "Parent-Order: " + ticker + " " + side);
        requestTracker.put(tpId, "Take-Profit: " + ticker);
        requestTracker.put(slId, "Stop-Loss: " + ticker);

        // 6. Use OrderFactory to create the bracket
        List<com.ib.client.Order> bracket = com.fgiaquinta.optionsquant.factories.OrderFactory.createOptionBracket(
                pId, tpId, slId, qty, underlyingConId, "SMART", entry, tp, sl, side.equalsIgnoreCase("CALL")
        );

        // --- DEEP INSPECTION LOG ---
        System.out.println("--------------------------------------------------");
        System.out.println("📝 [Inspection] Preparing Order for: " + ticker);
        System.out.println("   • Final Ticker: [" + contract.symbol() + "]"); // Brackets help see hidden spaces
        System.out.println("   • Expiration:   [" + contract.lastTradeDateOrContractMonth() + "]");
        System.out.println("   • Strike:       [" + contract.strike() + "]");
        System.out.println("   • Right:        [" + contract.right() + "]");
        System.out.println("   • SecType:      [" + contract.secType() + "]");
        System.out.println("   • Entry Price:  " + entry);
        System.out.println("   • Quantity:     " + qty);
        System.out.println("--------------------------------------------------");

        // 7. Send all three to IBKR
        for (com.ib.client.Order order : bracket) {
            orderIdToTicker.put(order.orderId(), ticker);
            client.placeOrder(order.orderId(), contract, order);
        }

        System.out.println("✅ [IBKR] Bracket sent! Parent ID: " + pId + " | Expiry: " + expiration + " | Strike: " + bestStrike + right);
    }

    public void disconnect() { client.eDisconnect(); }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        MarketRequest request = activeRequests.get(reqId);
        if (request != null) {
            org.ta4j.core.BarSeries series = marketData.get(request.getCacheKey());
            int totalBars = (series != null) ? series.getBarCount() : 0;

            System.out.println("✅ [BACKFILL COMPLETE] Loaded " + totalBars + " historical bars for " + request.ticker() + " [" + request.timeFrame() + "]. Now tracking LIVE.");
            // ADD THIS: Remove from pending list when done
            pendingBackfills.remove(reqId);
        }
    }

    /**
     * Blocks the main thread until all requested historical backfills have fired 'historicalDataEnd'
     */
    public void waitForBackfillCompletion() {
        System.out.println("⏳ Waiting for IBKR to finish all historical data downloads...");
        while (!pendingBackfills.isEmpty()) {
            try {
                // Sleep for a tiny fraction of a second, then check again
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("✅ All background data downloads are complete!");
    }

    @Override
    public void execDetails(int reqId, com.ib.client.Contract contract, com.ib.client.Execution execution) {
        String ticker = contract.symbol();
        String side = execution.side(); // "BOT" (Comprado) o "SLD" (Vendido)
        double price = execution.price();
        int shares = (int) execution.shares();

        System.out.println("✅ [EJECUCIÓN IBKR] " + side + " " + shares + " " + ticker + " @ " + price);

        // Si es una venta (cierre de un CALL o PUT), notificamos por Telegram
        if ("SLD".equalsIgnoreCase(side)) {
            String msg = "🔒 *TRADE CLOSED* \n" +
                    "Ticker: " + ticker + "\n" +
                    "Action: SOLD " + shares + " shares\n" +
                    "Fill Price: $" + price;
            com.fgiaquinta.optionsquant.services.TelegramService.sendSimpleMessage(msg);
        }
    }

    public void startMarketScreener() {
        System.out.println("📡 [Screener] Iniciando búsqueda de Top Gainers en el mercado...");

        com.ib.client.ScannerSubscription scanSub = new com.ib.client.ScannerSubscription();
        scanSub.instrument("STK"); // Acciones
        scanSub.locationCode("STK.US.MAJOR"); // Mercado de EEUU (NYSE, NASDAQ)
        scanSub.scanCode("TOP_PERC_GAIN"); // Las que más porcentaje suben

        // Pide los 10 mejores resultados. El ID 7000 es arbitrario para identificar el escáner.
        client.reqScannerSubscription(7000, scanSub, null, null);
    }

    @Override
    public void scannerData(int reqId, int rank, com.ib.client.ContractDetails contractDetails,
                            String distance, String benchmark, String projection, String legsStr) {

        String ticker = contractDetails.contract().symbol();

        // Si el ticker no está ya en tu MarketRadar, lo añadimos y lo guardamos
        if (marketRadar != null && !marketRadar.isHot(ticker)) {
            System.out.println("🔥 [Screener Hit] Ticker en tendencia detectado: " + ticker + " (Rank: " + rank + ")");
            marketRadar.addHotTicker(ticker);

            // Opcional: Empezar a seguir el precio en vivo para este nuevo ticker
            // startMarketDataTracking(ticker);
        }
    }

    @Override
    public void scannerDataEnd(int reqId) {
        System.out.println("✅ [Screener] Escaneo completado.");
        // Cancelamos la suscripción para que no siga consumiendo recursos infinitamente
        client.cancelScannerSubscription(reqId);
    }
}