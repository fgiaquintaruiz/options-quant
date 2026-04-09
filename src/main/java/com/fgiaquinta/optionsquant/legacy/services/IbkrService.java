package com.fgiaquinta.optionsquant.services;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.engine.AccountManager;
import com.fgiaquinta.optionsquant.engine.StrategyEngine;
import com.fgiaquinta.optionsquant.engine.TradeManager;
import com.fgiaquinta.optionsquant.engine.MarketRadar;
import com.fgiaquinta.optionsquant.factories.ContractFactory;
import com.fgiaquinta.optionsquant.models.MarketRequest;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.utils.DataManager;
import com.fgiaquinta.optionsquant.utils.MarketTimeUtils;
import org.ta4j.core.BarSeries;

import java.time.LocalDate;
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
    private final CountDownLatch accountLatch = new CountDownLatch(1);
    private final Map<String, Set<Double>> tickerToValidStrikes = new ConcurrentHashMap<>();
    // Maps Request ID -> Description (e.g., "Metadata: AAPL" or "Options: TSLA")
    private final Map<Integer, String> requestTracker = new ConcurrentHashMap<>();
    // 👉 NUEVO: Memoria para poder modificar Bracket Orders sin romper el Grupo OCA
    private final Map<Integer, com.ib.client.Contract> activeContracts = new ConcurrentHashMap<>();
    private final Map<Integer, com.ib.client.Order> activeOrders = new ConcurrentHashMap<>();

    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AccountManager accountManager;
    private MarketRadar marketRadar;
    private TradeManager tradeManager;

    public DataManager getDataManager() {
        return dataManager;
    }

    // 👉 AÑADE ESTA VARIABLE (junto al resto de tus Maps)
    private DataManager dataManager;

    // 👉 AÑADE ESTE MÉTODO (Puedes ponerlo debajo del constructor)
    public void setDataManager(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    // Required to prevent circular dependency at initialization
    public void setTradeManager(TradeManager tradeManager) {
        this.tradeManager = tradeManager;
    }

    @SuppressWarnings("this-escape")
    public IbkrService(AccountManager accountManager) {
        this.accountManager = accountManager;
        this.signal = new EJavaSignal();
        this.client = new EClientSocket(this, signal);
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

        // 👉 THE MISSING LINE: Subscribe to the account balance stream!
        // Passing "" (empty string) tells TWS to send data for your default active account.
        client.reqAccountUpdates(true, "");
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

    public String getOptimalExpiry(String ticker) { return tickerToBestExpiration.get(ticker); }

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

                // 👉 Safely notify TradeManager to update the Staircase Filter
                if (this.tradeManager != null) {
                    this.tradeManager.recordExit(ticker, lastFillPrice);
                } else {
                    System.err.println("⚠️ [IbkrService] TradeManager is not linked. Cannot record exit for forensic filter.");
                }

                // Clean up memory
                orderIdToTicker.values().removeIf(val -> val.equals(ticker));
            }
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
                    .toList();

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
        MarketRequest req = activeRequests.get(reqId);
        if (req == null) return;

        String ticker = req.ticker();
        com.fgiaquinta.optionsquant.models.TimeFrame timeFrame = req.timeFrame();

        // Usamos tu llave dinámica
        String cacheKey = ticker + "_" + timeFrame.getFileSuffix();

        // Obtenemos o creamos la "sala de espera" para este ticker
        BarSeries series = marketData.computeIfAbsent(cacheKey, k -> new org.ta4j.core.BaseBarSeriesBuilder().withName(cacheKey).build());

        // =========================================================
        // 👉 ARREGLO DEL BUG DE 1970 (Parseo inteligente de fechas)
        // =========================================================
        String dateStr = bar.time();
        java.time.ZonedDateTime zdt;

        try {
            if (dateStr.length() == 8) {
                // Es una vela DIARIA (Formato: yyyyMMdd)
                java.time.LocalDate date = java.time.LocalDate.parse(dateStr, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
                // Le asignamos el cierre oficial del mercado de NY (16:00) para evitar desfases
                zdt = date.atTime(16, 0).atZone(java.time.ZoneId.of("America/New_York"));
            } else {
                // Es una vela INTRADÍA (Formato: yyyyMMdd  HH:mm:ss) - Ojo, son dos espacios en el medio
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(dateStr, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd  HH:mm:ss"));
                zdt = ldt.atZone(java.time.ZoneId.of("America/New_York"));
            }

            series.addBar(zdt, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());

        } catch (Exception e) {
            System.err.println("❌ Error parseando fecha de IBKR '" + dateStr + "': " + e.getMessage());
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
        MarketRequest req = activeRequests.remove(reqId);
        if (req == null) return;

        String ticker = req.ticker();
        com.fgiaquinta.optionsquant.models.TimeFrame timeFrame = req.timeFrame();

        // Recuperamos los datos recién llegados
        org.ta4j.core.BarSeries ibkrSeries = marketData.remove(ticker + "_" + timeFrame.getFileSuffix());
        org.ta4j.core.BarSeries localSeries = dataManager.getSeries(ticker, timeFrame);

        if (localSeries != null && !localSeries.isEmpty()) {
            // --- CASO A: EMPALME INTELIGENTE ---
            if (ibkrSeries != null && !ibkrSeries.isEmpty()) {
                int added = 0;
                for (int i = 0; i < ibkrSeries.getBarCount(); i++) {
                    org.ta4j.core.Bar newBar = ibkrSeries.getBar(i);
                    if (newBar.getEndTime().isAfter(localSeries.getLastBar().getEndTime())) {
                        localSeries.addBar(newBar.getEndTime(), newBar.getOpenPrice(), newBar.getHighPrice(), newBar.getLowPrice(), newBar.getClosePrice(), newBar.getVolume());
                        added++;
                    }
                }

                if (added > 0) {
                    System.out.println("✅ [MERGE] " + ticker + " [" + timeFrame + "] -> " + added + " velas nuevas.");
                    // 👉 ¡LA PIEZA FALTANTE! Guardamos los cambios en el archivo
                    dataManager.saveSeriesToCsv(ticker, timeFrame);
                }
            }
        } else {
            // --- CASO B: DESCARGA DESDE CERO (No había CSV) ---
            if (ibkrSeries != null && !ibkrSeries.isEmpty()) {
                dataManager.putSeries(ticker, timeFrame, ibkrSeries);
                System.out.println("✅ [BACKFILL] Creado historial para " + ticker + " [" + timeFrame + "].");

                // 👉 Guardamos el nuevo archivo en la carpeta data/
                dataManager.saveSeriesToCsv(ticker, timeFrame);
            }
        }
    }

    /**
     * Fix: Updated signature to 5 parameters to match your API version.
     */
    @Override
    public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        String context = requestTracker.getOrDefault(id, "Unknown Request");

        if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;

        System.err.println("❌ [IBKR Error]");
        System.err.println("   ID:       " + id);
        System.err.println("   Context:  " + context);
        System.err.println("   Code:     " + errorCode);
        System.err.println("   Message:  " + errorMsg);
        System.err.println("--------------------------------------------------");

        // 👉 THE FIX: Si falla un Request, elimínalo de los pendingBackfills para no congelar el bot
        if (id != -1 && pendingBackfills.contains(id)) {
            pendingBackfills.remove(id);
            System.err.println("⚠️ [Sistema Salvado] Soltando el request " + id + " (" + context + ") de la cola de espera por error.");
        }
    }

    public int placeOrder(String ticker, String side, int qty, double entry, double tp, double sl, String strategy) {
        System.out.println("🚀 [IBKR] Placing " + side + " order for " + ticker + " | Qty: " + qty + " | Strategy: " + strategy);

        // 1. Get the dynamic expiration
        String expiration = getOptimalExpiry(ticker);
        if (expiration == null) {
            System.err.println("❌ Aborting: No expiration found for " + ticker);
            return -1; // 👉 Modificado para devolver -1 en error
        }

        // 2. Get the Underlying Contract ID
        Integer underlyingConId = tickerToConId.get(ticker);
        if (underlyingConId == null) {
            System.err.println("❌ Aborting: Underlying Contract ID not found for " + ticker);
            return -1; // 👉 Modificado
        }

        // 3. FIND THE REAL STRIKE (Fix for Error 200)
        java.util.Set<Double> strikes = tickerToValidStrikes.get(ticker);
        if (strikes == null || strikes.isEmpty()) {
            System.err.println("❌ Aborting: No valid strikes loaded for " + ticker + ". Check metadata loading.");
            return -1; // 👉 Modificado
        }

        // Use Java Streams to find the strike with the smallest difference from the entry price
        double bestStrike = strikes.stream()
                .min(java.util.Comparator.comparingDouble(s -> Math.abs(s - entry)))
                .orElse((double) Math.round(entry));

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

        // 👉 FIX ERROR 398: Determinar el Exchange real para el Trigger de Precio
        com.ib.client.Contract stockDef = com.fgiaquinta.optionsquant.factories.ContractFactory.createStockDefinition(ticker);
        String triggerExchange = stockDef.primaryExch();

        if (triggerExchange == null || triggerExchange.isEmpty() || triggerExchange.equals("SMART")) {
            triggerExchange = "ISLAND";
        }

        // 6. Use OrderFactory to create the bracket
        java.util.List<com.ib.client.Order> bracket = com.fgiaquinta.optionsquant.factories.OrderFactory.createOptionBracket(
                pId, tpId, slId, qty, underlyingConId, triggerExchange, entry, tp, sl, side.equalsIgnoreCase("CALL")
        );

        System.out.println("--------------------------------------------------");
        System.out.println("📝 [Inspection] Preparing Order for: " + ticker);
        System.out.println("   • Final Ticker: [" + contract.symbol() + "]");
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

            // 👉 NUEVO: Guardamos el contrato y la orden intacta para poder modificarla luego
            activeContracts.put(order.orderId(), contract);
            activeOrders.put(order.orderId(), order);

            client.placeOrder(order.orderId(), contract, order);
        }

        System.out.println("✅ [IBKR] Bracket sent! Parent ID: " + pId + " | Expiry: " + expiration + " | Strike: " + bestStrike + right);

        // 👉 NUEVO: Devolvemos el ID del Stop Loss al TradeManager
        return slId;
    }

    // 👉 NUEVO MÉTODO: Actualiza el Stop Loss dinámicamente en Wall Street
    public void modifyStopLossCondition(int slOrderId, String ticker, double newPriceCondition) {
        // 1. Recuperamos la orden y el contrato original de nuestra caché
        com.ib.client.Order slOrder = activeOrders.get(slOrderId);
        com.ib.client.Contract contract = activeContracts.get(slOrderId);
        Integer underlyingConId = tickerToConId.get(ticker);

        if (slOrder == null || contract == null || underlyingConId == null) {
            System.err.println("⚠️ [IBKR] No se pudo modificar el SL " + slOrderId + ". Orden no encontrada en memoria.");
            return;
        }

        System.out.printf("🔧 [IBKR Trailing Stop] Subiendo Stop Loss de %s al nuevo precio: $%.2f%n", ticker, newPriceCondition);

        // 2. Limpiamos las condiciones de precio anteriores que tenía esta orden
        slOrder.conditions().clear();

        // 3. Calculamos la nueva condición:
        // Si es CALL (Right = "C"), el SL se activa si la acción CAE por debajo (isMore = false)
        // Si es PUT (Right = "P"), el SL se activa si la acción SUBE por encima (isMore = true)
        boolean isCall = contract.right().equals("C");
        boolean isMore = !isCall;

        // 4. Agregamos la nueva condición de precio de la acción
        com.ib.client.OrderCondition newCondition = com.fgiaquinta.optionsquant.builders.ConditionBuilder.createPriceCondition(
                underlyingConId, "SMART", isMore, newPriceCondition
        );
        slOrder.conditions().add(newCondition);

        // 5. Reenviamos a IBKR con el MISMO orderId. IBKR interpretará esto como un "Update" y no romperá el Bracket.
        client.placeOrder(slOrderId, contract, slOrder);
    }

    public void disconnect() { client.eDisconnect(); }

    /**
     * Blocks the main thread until all requested historical backfills have fired 'historicalDataEnd'
     */
    public void waitForBackfillCompletion() {
        System.out.println("⏳ Waiting for IBKR to finish all historical data downloads...");

        while (!pendingBackfills.isEmpty()) {
            try {
                Thread.sleep(200); // Pausa breve para no saturar la CPU
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("✅ All background data downloads are complete!");
    }

    @Override
    public void execDetails(int reqId, com.ib.client.Contract contract, com.ib.client.Execution execution) {
        String ticker = contract.symbol();
        String side = execution.side(); // "BOT" (Comprado) o "SLD" (Vendido)
        double price = execution.price();
        int shares = execution.shares().value().intValue();

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
        System.out.println("📡 [Screener] Starting scan for Top Gainers (CALLs) and Top Losers (PUTs)...");

        // 1. Gainers Scan (For Call Strategies)
        com.ib.client.ScannerSubscription scanGainers = new com.ib.client.ScannerSubscription();
        scanGainers.instrument("STK");
        scanGainers.locationCode("STK.US.MAJOR");
        scanGainers.scanCode("TOP_PERC_GAIN");
        client.reqScannerSubscription(7000, scanGainers, null, null);

        // 2. Losers Scan (For Put Strategies)
        com.ib.client.ScannerSubscription scanLosers = new com.ib.client.ScannerSubscription();
        scanLosers.instrument("STK");
        scanLosers.locationCode("STK.US.MAJOR");
        scanLosers.scanCode("TOP_PERC_LOSE"); // IBKR code for biggest drops
        client.reqScannerSubscription(7001, scanLosers, null, null);
    }

    @Override
    public void scannerData(int reqId, int rank, com.ib.client.ContractDetails contractDetails,
                            String distance, String benchmark, String projection, String legsStr) {

        String ticker = contractDetails.contract().symbol();

        // Identify the context based on the request ID we assigned
        String trendType = (reqId == 7000) ? "BULLISH 🟢" : "BEARISH 🔴";

        if (marketRadar != null && !marketRadar.isHot(ticker)) {
            System.out.println("🔥 [Screener] " + trendType + " Ticker detected: " + ticker + " (Rank: " + rank + ")");
            marketRadar.addHotTicker(ticker);

            startMarketDataTracking(ticker);
        }
    }

    @Override
    public void scannerDataEnd(int reqId) {
        if (reqId == 7000) System.out.println("✅ [Screener] Top Gainers scan complete.");
        if (reqId == 7001) System.out.println("✅ [Screener] Top Losers scan complete.");

        // Free up API resources once the lists are populated
        client.cancelScannerSubscription(reqId);
    }

    private String calculateDeltaDuration(org.ta4j.core.BarSeries series, com.fgiaquinta.optionsquant.models.TimeFrame tf) {
        if (series == null || series.isEmpty()) {
            return tf.getIbkrDuration(); // Fallback: Descarga inicial
        }

        java.time.ZonedDateTime lastBarTime = series.getLastBar().getEndTime();
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(lastBarTime.getZone());
        long secondsBetween = java.time.Duration.between(lastBarTime, now).getSeconds();

        if (secondsBetween <= 0) return "60 S"; // Mínimo permitido por IBKR

        long days = (secondsBetween / 86400) + 1; // +1 día por margen de seguridad

        // 👉 FIX: Límites máximos absolutos (Hard Caps) para evitar el Error 162
        switch (tf) {
            case MIN_5:
                if (days > 5) return "5 D";   // IBKR solo suele dar ~5-7 días de velas de 1 min
                break;
            case MIN_15:
                if (days > 20) return "20 D"; // Max ~20 días para 15 min
                break;
            case HOUR_1:
                if (days > 60) return "2 M";  // Max ~2 meses para 1 hora
                break;
            case DAY_1:
                if (days > 1825) return "5 Y"; // Max 5 años para diarias
                break;
        }

        // Convertir la diferencia al formato estricto de IBKR
        if (secondsBetween < 86400) {
            return secondsBetween + " S";
        } else if (days > 365) {
            long years = (days / 365) + 1;
            return years + " Y";
        } else {
            return days + " D";
        }
    }

    // Default durations if no CSV exists
    private String getDefaultDuration(TimeFrame tf) {
        switch (tf) {
            case MIN_5: return "5 D";
            case MIN_15: return "20 D";
            case HOUR_1: return "2 M";
            case DAY_1: return "2 Y";
            default: return "1 M";
        }
    }

    public void startMarketDataTracking(String ticker) {
        System.out.println("📡 Iniciando tracking para: " + ticker);
        com.ib.client.Contract contract = ContractFactory.createStockDefinition(ticker);

        for (TimeFrame tf : TimeFrame.values()) {
            MarketRequest request = new MarketRequest(ticker, tf);
            String cacheKey = request.getCacheKey();

            // 1. Intentar cargar los datos históricos desde el CSV local
            org.ta4j.core.BarSeries localSeries = DataManager.loadSeries(cacheKey);

            // Guardar la serie en memoria (solo si existe, si no, se inicializará luego)
            if (localSeries != null) {
                marketData.put(cacheKey, localSeries);
            }

            // 2. Calcular el Delta usando TU MÉTODO con Hard Caps
            // Tu método ya maneja internamente si series es null o está vacía
            String deltaDuration = calculateDeltaDuration(localSeries, tf);

            // 3. Preparar la petición a IBKR
            int id = nextId.getAndIncrement();
            activeRequests.put(id, request); // Guardamos la petición para saber qué nos devuelve IBKR
            requestTracker.put(id, "Live-Data [" + tf + "]: " + ticker); // Para el log de errores

            System.out.println("🔄 [" + tf + "] " + ticker + " Pidiendo Delta: " + deltaDuration);
            pendingBackfills.add(id);
            client.reqHistoricalData(id, contract, "", deltaDuration, tf.getIbkrBarSize(), "TRADES", 1, 1, false, null);
        }
    }

    @Override
    public void updateAccountValue(String key, String value, String currency, String accountName) {
        if ("NetLiquidation".equals(key)) {
            System.out.println("🏦 [IBKR] Received Account Update: " + key + " = " + value + " " + currency);
            accountManager.updateBalance(accountName, Double.parseDouble(value));

            accountLatch.countDown();
        }
    }

    public boolean waitForAccountSync(int timeoutSeconds) {
        try {
            System.out.println("⏳ Waiting for Account Balance from IBKR...");
            return accountLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            System.out.println("InterruptedException in waitForAccountSync...");
            return false;
        }
    }

    // 👉 MÉTODO ACTUALIZADO: Ahora es inteligente y revisa el disco primero
    public void requestHistoricalDataForCache(String ticker, TimeFrame timeFrame) {

        // 1. PRIMERO REVISAMOS EL ARCHIVO LOCAL
        boolean isLocalDataLoaded = dataManager.loadLocalDataFromCsv(ticker, timeFrame);

        int reqId = nextId.getAndIncrement();
        activeRequests.put(reqId, new MarketRequest(ticker, timeFrame));

        Contract contract = ContractFactory.createStockDefinition(ticker);

        String duration;
        String barSize;

        // 2. DECIDIMOS CUÁNTA INFORMACIÓN PEDIR A LA API
        if (isLocalDataLoaded) {
            // Si ya tenemos el pasado en el CSV, solo pedimos un "relleno" corto (2 Días)
            // para conseguir las velas de hoy y empalmarlas con el historial de ayer.
            duration = "2 D";
            if (timeFrame == com.fgiaquinta.optionsquant.models.TimeFrame.MIN_15) {
                barSize = "15 mins";
            } else if (timeFrame == com.fgiaquinta.optionsquant.models.TimeFrame.HOUR_1) {
                barSize = "1 hour";
            } else if (timeFrame == com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1) {
                barSize = "1 day";
            } else {
                barSize = "5 mins";
            }
            System.out.println("🔄 [Local Cache OK] Pidiendo solo relleno vivo (2 Días) " + barSize + " para " + ticker + "...");

        } else {
            if (timeFrame == com.fgiaquinta.optionsquant.models.TimeFrame.MIN_15) {
                duration = "5 D";
                barSize = "15 mins";
            } else if (timeFrame == com.fgiaquinta.optionsquant.models.TimeFrame.HOUR_1) {
                duration = "10 D";
                barSize = "1 hour";
            } else if (timeFrame == com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1) {
                duration = "1 Y"; // Un año para la SMA 200
                barSize = "1 day";
            } else {
                duration = "2 D";
                barSize = "5 mins";
            }
            System.out.println("⚠️ [No Local Cache] Solicitando historial COMPLETO (" + duration + ") " + barSize + " para " + ticker + "...");
        }

        // Petición a IBKR
        client.reqHistoricalData(reqId, contract, "", duration, barSize, "TRADES", 1, 1, false, null);
    }
}