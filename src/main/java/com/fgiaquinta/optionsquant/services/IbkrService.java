package com.fgiaquinta.optionsquant.services;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.builders.ConditionBuilder;
import com.fgiaquinta.optionsquant.factories.ContractFactory;
import com.fgiaquinta.optionsquant.factories.OrderFactory;
import com.fgiaquinta.optionsquant.engine.ForensicEngine;
import com.fgiaquinta.optionsquant.engine.StrategyEngine;
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

    private final Map<Integer, String> reqToTicker = new ConcurrentHashMap<>();
    private final Map<String, BarSeries> marketData = new ConcurrentHashMap<>();
    private final Map<String, Integer> tickerToConId = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToPrimaryExch = new ConcurrentHashMap<>();

    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();
    // NUEVO MAPA: Almacena los Strikes válidos del mercado
    private final Map<String, TreeSet<Double>> tickerToStrikes = new ConcurrentHashMap<>();

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
                try { reader.processMsgs(); } catch (Exception e) { e.printStackTrace(); }
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

    public void startMarketDataTracking(String ticker) {
        BarSeries s1h = DataManager.loadSeries(ticker, "1 hour");
        marketData.put(ticker + "_1hour", s1h);

        Contract contract = new Contract();
        contract.symbol(ticker);
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");

        client.reqContractDetails(nextId.getAndIncrement(), contract);

        int reqId = nextId.getAndIncrement();
        reqToTicker.put(reqId, ticker + "_1hour");
        client.reqHistoricalData(reqId, contract, "", "2 D", "1 hour", "TRADES", 1, 1, false, null);
    }

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
        // Filtramos para asegurar que solo guardamos cadenas de opciones estándar (multiplicador 100)
        if (!"100".equals(multiplier)) return;
        if (tickerToBestExpiration.containsKey(tradingClass)) return;

        String bestDate = expirations.stream()
                .filter(date -> {
                    try {
                        LocalDate exp = LocalDate.parse(date, DateTimeFormatter.ofPattern("yyyyMMdd"));
                        return exp.isAfter(LocalDate.now().plusDays(2));
                    } catch (Exception e) { return false; }
                })
                .min(String::compareTo)
                .orElse(null);

        if (bestDate != null) {
            tickerToBestExpiration.put(tradingClass, bestDate);
            // GUARDAMOS LOS STRIKES VÁLIDOS
            if (strikes != null) {
                tickerToStrikes.put(tradingClass, new TreeSet<>(strikes));
            }
            System.out.println("📅 Vencimiento optimo detectado para " + tradingClass + ": " + bestDate);
        }
    }

    public boolean placeOrder(String ticker, String action, int qtyIgnored, double entry, double tp, double sl, String strategyName) {
        try {
            if (!initialSync.await(10, TimeUnit.SECONDS)) return false;

            Integer subConId = tickerToConId.get(ticker);
            String expiry = tickerToBestExpiration.get(ticker);
            String primaryExch = tickerToPrimaryExch.get(ticker);
            TreeSet<Double> validStrikes = tickerToStrikes.get(ticker);

            if (subConId == null || expiry == null) return false;

            // LÓGICA DE STRIKE SNAPPING (Busca el Strike oficial más cercano)
            double finalStrike = Math.round(entry);
            if (validStrikes != null && !validStrikes.isEmpty()) {
                finalStrike = validStrikes.stream()
                        .min(Comparator.comparingDouble(s -> Math.abs(s - entry)))
                        .orElse((double) Math.round(entry));
            }

            boolean isCall = strategyName.contains("CALL");

            Contract contract = ContractFactory.createOptionContract(
                    ticker,
                    expiry,
                    finalStrike, // Usamos el Strike validado
                    isCall ? "C" : "P"
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
                    ticker,
                    exec.side(),
                    exec.avgPrice(),
                    exec.cumQty().value().doubleValue(),
                    report.commissionAndFees(),
                    eId
            );
        }
    }

    @Override
    public void historicalData(int reqId, com.ib.client.Bar bar) {
        String key = reqToTicker.get(reqId);
        BarSeries series = marketData.get(key);
        if (series != null) {
            try {
                String t = bar.time();
                ZonedDateTime time = t.contains(" ") ?
                        ZonedDateTime.parse(t.replaceAll("\\s+", " "), DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z")).withZoneSameInstant(ZoneId.of("Europe/Madrid")) :
                        ZonedDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(t)), ZoneId.of("Europe/Madrid"));

                if (series.getBarCount() == 0 || time.isAfter(series.getLastBar().getEndTime())) {
                    series.addBar(time, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());
                }
            } catch (Exception e) {}
        }
    }

    @Override
    public void historicalDataEnd(int reqId, String start, String end) {
        String key = reqToTicker.get(reqId);
        DataManager.saveToCsv(marketData.get(key));
        if (strategyEngine != null && key.endsWith("1hour")) {
            strategyEngine.onBarAdded(key.split("_")[0], marketData.get(key));
        }
    }

    public BarSeries getSeries(String key) { return marketData.get(key); }

    @Override
    public void error(int id, long time, int errorCode, String errorMsg, String advancedOrderRejectJson) {
        if (errorCode >= 2000) return;
        System.err.println("⚠️ [IBKR " + errorCode + "] " + errorMsg);
    }
}