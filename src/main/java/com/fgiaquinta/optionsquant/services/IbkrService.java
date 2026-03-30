package com.fgiaquinta.optionsquant.services;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.factories.ContractFactory;
import com.fgiaquinta.optionsquant.factories.OrderFactory;
import com.fgiaquinta.optionsquant.engine.ForensicEngine;
import com.fgiaquinta.optionsquant.engine.StrategyEngine;
import com.fgiaquinta.optionsquant.models.MarketRequest;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.DataManager;
import com.fgiaquinta.optionsquant.utils.ForensicLogger;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class IbkrService extends DefaultEWrapper {
    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AtomicInteger nextId = new AtomicInteger(0);
    private final CountDownLatch initialSync = new CountDownLatch(1);

    // Mapas Multi-Temporalidad (Multi-Timeframe)
    private final Map<Integer, MarketRequest> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, BarSeries> marketData = new ConcurrentHashMap<>();

    // Mapas de definición de contratos y opciones
    private final Map<String, Integer> tickerToConId = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToPrimaryExch = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();
    private final Map<String, TreeSet<Double>> tickerToStrikes = new ConcurrentHashMap<>();

    // Mapas de auditoría forense
    private final Map<String, Execution> pendingExecutions = new ConcurrentHashMap<>();
    private final Map<String, String> execIdToTicker = new ConcurrentHashMap<>();

    private StrategyEngine strategyEngine;
    private ForensicEngine forensicEngine;

    public IbkrService() {
        this.signal = new EJavaSignal();
        this.client = new EClientSocket(this, signal);
        client.eConnect("127.0.0.1", 7497, 0);

        Thread.ofVirtual().start(() -> {
            final EReader reader = new EReader(client, signal);
            reader.start();
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void nextValidId(int orderId) {
        nextId.set(orderId);
        initialSync.countDown();
        System.out.println("🆔 Sincronizado OrderID inicial: " + orderId);
    }

    public void setStrategyEngine(StrategyEngine engine) { this.strategyEngine = engine; }
    public void setForensicEngine(ForensicEngine engine) { this.forensicEngine = engine; }

    // --- CARGADOR DE DATOS MULTI-TEMPORAL ---

    public void startMarketDataTracking(String ticker) {
        // 1. Pedir detalles del contrato para enrutamiento de opciones
        Contract contract = ContractFactory.createStockDefinition(ticker);
        client.reqContractDetails(nextId.getAndIncrement(), contract);

        // 2. Iterar sobre todas las temporalidades requeridas (SRP & DRY)
        for (TimeFrame tf : TimeFrame.values()) {
            MarketRequest request = new MarketRequest(ticker, tf);
            String cacheKey = request.getCacheKey();

            // Cargar datos existentes para no descargar historial duplicado
            BarSeries series = DataManager.loadSeries(cacheKey);
            marketData.put(cacheKey, series);

            int reqId = nextId.getAndIncrement();
            activeRequests.put(reqId, request);

            // Solicitar actualizaciones en vivo e historial
            client.reqHistoricalData(reqId, contract, "", tf.getIbkrDuration(), tf.getIbkrBarSize(), "TRADES", 1, 1, false, null);
        }
    }

    @Override
    public void historicalData(int reqId, com.ib.client.Bar bar) {
        MarketRequest request = activeRequests.get(reqId);
        if (request == null) return;

        BarSeries series = marketData.get(request.getCacheKey());
        if (series != null) {
            try {
                String t = bar.time();
                ZonedDateTime time = t.contains(" ") ?
                        ZonedDateTime.parse(t.replaceAll("\\s+", " "), DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z")).withZoneSameInstant(ZoneId.of("Europe/Madrid")) :
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(t)), ZoneId.of("Europe/Madrid"));

                if (series.getBarCount() == 0 || time.isAfter(series.getLastBar().getEndTime())) {
                    series.addBar(time, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());
                }
            } catch (Exception e) {
                // Ignorar silenciosamente velas corruptas
            }
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String start, String end) {
        MarketRequest request = activeRequests.get(reqId);
        if (request == null) return;

        String key = request.getCacheKey();

        // Notificar al DataManager para guardar la temporalidad específica
        // Nota: Tendremos que actualizar DataManager para soportar el cacheKey
        DataManager.saveToCsv(marketData.get(key));

        // Disparar el motor de estrategias SOLO cuando cierra la vela más rápida (15 mins)
        if (strategyEngine != null && request.timeFrame() == TimeFrame.MIN_15) {
            strategyEngine.onBarAdded(request.ticker(), marketData.get(key));
        }
    }

    // --- ACCESO A DATOS MULTI-TEMPORAL ---

    public BarSeries getSeries(String ticker, TimeFrame tf) {
        return marketData.get(new MarketRequest(ticker, tf).getCacheKey());
    }

    // --- DEFINICIÓN DE CONTRATOS Y OPCIONES ---

    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        String ticker = contractDetails.contract().symbol();
        int conId = contractDetails.contract().conid();
        String primaryExch = contractDetails.contract().primaryExch();

        tickerToConId.put(ticker, conId);
        if (primaryExch != null && !primaryExch.isEmpty()) {
            tickerToPrimaryExch.put(ticker, primaryExch);
        }

        client.reqSecDefOptParams(nextId.getAndIncrement(), ticker, "", "STK", conId);
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
        // Filtramos para asegurar que solo guardamos cadenas estándar (multiplicador 100)
        if (!"100".equals(multiplier) || tickerToBestExpiration.containsKey(tradingClass)) return;

        String bestDate = expirations.stream()
                .filter(date -> {
                    try {
                        return LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd")).isAfter(LocalDate.now().plusDays(2));
                    } catch (Exception e) { return false; }
                }).min(String::compareTo).orElse(null);

        if (bestDate != null) {
            tickerToBestExpiration.put(tradingClass, bestDate);
            if (strikes != null) {
                tickerToStrikes.put(tradingClass, new TreeSet<>(strikes));
            }
            System.out.println("📅 Vencimiento optimo detectado para " + tradingClass + ": " + bestDate);
        }
    }

    // --- EJECUCIÓN Y ÓRDENES ---

    public boolean placeOrder(String ticker, String action, int qtyIgnored, double entry, double tp, double sl, String strategyName) {
        try {
            if (!initialSync.await(10, TimeUnit.SECONDS)) return false;

            Integer subConId = tickerToConId.get(ticker);
            String expiry = tickerToBestExpiration.get(ticker);
            String primaryExch = tickerToPrimaryExch.get(ticker);
            TreeSet<Double> validStrikes = tickerToStrikes.get(ticker);

            if (subConId == null || expiry == null) return false;

            // Lógica de Strike Snapping: Buscar el strike válido más cercano
            double finalStrike = Math.round(entry);
            if (validStrikes != null && !validStrikes.isEmpty()) {
                finalStrike = validStrikes.stream()
                        .min(Comparator.comparingDouble(s -> Math.abs(s - entry)))
                        .orElse((double) Math.round(entry));
            }

            boolean isCall = strategyName.contains("CALL");

            Contract contract = ContractFactory.createOptionContract(
                    ticker, expiry, finalStrike, isCall ? "C" : "P"
            );

            int pId = nextId.getAndIncrement();
            int tpId = nextId.getAndIncrement();
            int slId = nextId.getAndIncrement();

            List<Order> bracket = OrderFactory.createOptionBracket(
                    pId, tpId, slId, 10, subConId, primaryExch, entry, tp, sl, isCall
            );

            for (Order o : bracket) {
                client.placeOrder(o.orderId(), contract, o);
            }

            System.out.printf("🎯 OPTION SENT: 10x %s %s Strike %.1f | Exp %s%n",
                    ticker, contract.right(), finalStrike, expiry);
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // --- AUDITORÍA FORENSE ---

    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        String eId = execution.execId();
        execIdToTicker.put(eId, contract.symbol());
        pendingExecutions.put(eId, execution);
        System.out.printf("🔔 FILL: %s %s a %.2f%n", contract.symbol(), execution.side(), execution.avgPrice());
    }

    @Override
    public void commissionAndFeesReport(CommissionAndFeesReport report) {
        String eId = report.execId();
        Execution exec = pendingExecutions.remove(eId);
        String ticker = execIdToTicker.remove(eId);

        if (exec != null && ticker != null) {
            ForensicLogger.logExecution(
                    ticker, exec.side(), exec.avgPrice(),
                    exec.cumQty().value().doubleValue(), report.commissionAndFees(), eId
            );
        }
    }

    @Override
    public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (errorCode >= 2000) return;
        System.err.println("⚠️ [IBKR " + errorCode + "] " + errorMsg);
    }
}