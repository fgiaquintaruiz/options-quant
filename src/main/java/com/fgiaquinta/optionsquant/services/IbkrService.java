package com.fgiaquinta.optionsquant.services;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.factories.ContractFactory;
import com.fgiaquinta.optionsquant.factories.OrderFactory;
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

    // DOBLE CONTADOR PARA EVITAR CONFLICTOS
    private final AtomicInteger nextId = new AtomicInteger(1000); // Para peticiones de datos
    private final AtomicInteger nextOrderId = new AtomicInteger(0); // Para órdenes (sincronizado por TWS)

    private final CountDownLatch initialSync = new CountDownLatch(1);
    private final Map<Integer, MarketRequest> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, BarSeries> marketData = new ConcurrentHashMap<>();
    private final Map<String, Integer> tickerToConId = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToPrimaryExch = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();
    private final Map<String, TreeSet<Double>> tickerToStrikes = new ConcurrentHashMap<>();
    private final Map<String, Execution> pendingExecutions = new ConcurrentHashMap<>();
    private final Map<String, String> execIdToTicker = new ConcurrentHashMap<>();

    private StrategyEngine strategyEngine;

    public IbkrService(int clientId) {
        this.signal = new EJavaSignal();
        this.client = new EClientSocket(this, signal);
        client.eConnect("127.0.0.1", 7497, clientId);

        Thread.ofVirtual().start(() -> {
            final EReader reader = new EReader(client, signal);
            reader.start();
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) { e.printStackTrace(); }
            }
        });
    }

    @Override
    public void nextValidId(int orderId) {
        nextOrderId.set(orderId); // Sincronizamos el contador de órdenes oficial
        initialSync.countDown();
        System.out.println("🆔 Sincronizado OrderID inicial: " + orderId);
    }

    public void setStrategyEngine(StrategyEngine engine) { this.strategyEngine = engine; }

    public void startMarketDataTracking(String ticker) {
        Contract contract = ContractFactory.createStockDefinition(ticker);
        client.reqContractDetails(nextId.getAndIncrement(), contract);

        for (TimeFrame tf : TimeFrame.values()) {
            MarketRequest request = new MarketRequest(ticker, tf);
            String cacheKey = request.getCacheKey();
            BarSeries series = DataManager.loadSeries(cacheKey);
            marketData.put(cacheKey, series);

            int reqId = nextId.getAndIncrement();
            activeRequests.put(reqId, request);
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
                        ZonedDateTime.parse(t.replaceAll("\\s+", " "), DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z")) :
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(t)), ZoneId.of("America/New_York"));

                if (series.getBarCount() == 0 || time.isAfter(series.getLastBar().getEndTime())) {
                    series.addBar(time, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());
                }
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String start, String end) {
        MarketRequest request = activeRequests.get(reqId);
        if (request == null) return;
        DataManager.saveToCsv(marketData.get(request.getCacheKey()));

        if (strategyEngine != null && request.timeFrame() == TimeFrame.MIN_15) {
            strategyEngine.onBarAdded(request.ticker(), marketData.get(request.getCacheKey()));
        }
    }

    public BarSeries getSeries(String ticker, TimeFrame tf) {
        return marketData.get(new MarketRequest(ticker, tf).getCacheKey());
    }

    @Override
    public void contractDetails(int reqId, ContractDetails contractDetails) {
        String ticker = contractDetails.contract().symbol();
        tickerToConId.put(ticker, contractDetails.contract().conid());
        if (contractDetails.contract().primaryExch() != null) {
            tickerToPrimaryExch.put(ticker, contractDetails.contract().primaryExch());
        }
        client.reqSecDefOptParams(nextId.getAndIncrement(), ticker, "", "STK", contractDetails.contract().conid());
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
        if (!"100".equals(multiplier) || tickerToBestExpiration.containsKey(tradingClass)) return;

        String bestDate = expirations.stream()
                .filter(date -> LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd")).isAfter(LocalDate.now().plusDays(2)))
                .min(String::compareTo).orElse(null);

        if (bestDate != null) {
            tickerToBestExpiration.put(tradingClass, bestDate);
            tickerToStrikes.put(tradingClass, new TreeSet<>(strikes));
            System.out.println("📅 Vencimiento optimo detectado para " + tradingClass + ": " + bestDate);
        }
    }

    public boolean placeOrder(String ticker, String action, int qty, double entry, double tp, double sl, String strategyName) {
        try {
            if (!initialSync.await(5, TimeUnit.SECONDS)) return false;

            Integer subConId = tickerToConId.get(ticker);
            String expiry = tickerToBestExpiration.get(ticker);
            TreeSet<Double> validStrikes = tickerToStrikes.get(ticker);

            if (subConId == null || expiry == null || validStrikes == null) return false;

            double finalStrike = validStrikes.stream()
                    .min(Comparator.comparingDouble(s -> Math.abs(s - entry))).get();

            boolean isCall = strategyName.contains("CALL");
            Contract contract = ContractFactory.createOptionContract(ticker, expiry, finalStrike, isCall ? "C" : "P");

            // USAR nextOrderId PARA EL BRACKET
            int pId = nextOrderId.getAndIncrement();
            int tpId = nextOrderId.getAndIncrement();
            int slId = nextOrderId.getAndIncrement();

            List<Order> bracket = OrderFactory.createOptionBracket(pId, tpId, slId, 10, subConId, tickerToPrimaryExch.get(ticker), entry, tp, sl, isCall);

            for (Order o : bracket) client.placeOrder(o.orderId(), contract, o);

            System.out.printf("🎯 OPTION SENT: 10x %s %s Strike %.1f | Exp %s%n", ticker, contract.right(), finalStrike, expiry);
            return true;
        } catch (Exception e) { return false; }
    }

    @Override
    public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        // SILENCIAR AVISOS INFORMATIVOS (Incluyendo el 399 de mercado cerrado)
        if (errorCode >= 2000 || errorCode == 399 || errorCode == 2104 || errorCode == 2106) return;
        System.err.println("⚠️ [IBKR " + errorCode + "] " + errorMsg);
    }
}