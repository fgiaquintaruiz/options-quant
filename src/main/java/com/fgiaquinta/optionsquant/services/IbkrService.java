package com.fgiaquinta.optionsquant.services;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.engine.ForensicEngine;
import com.fgiaquinta.optionsquant.engine.StrategyEngine;
import com.fgiaquinta.optionsquant.utils.DataManager;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class IbkrService extends DefaultEWrapper {
    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AtomicInteger nextId = new AtomicInteger(7000);

    private final Map<Integer, String> reqToTicker = new ConcurrentHashMap<>();
    private final Map<String, BarSeries> marketData = new ConcurrentHashMap<>();
    private final Map<String, Integer> tickerToConId = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();
    private final Map<String, Execution> pendingExecutions = new ConcurrentHashMap<>();
    private final CountDownLatch initialSync = new CountDownLatch(1);

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
        // Correct line for current API version
        int conId = contractDetails.contract().conid();
        tickerToConId.put(ticker, conId);
        client.reqSecDefOptParams(nextId.getAndIncrement(), ticker, "", "STK", conId);
    }

    @Override
    public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
        // FILTRO 1: Si ya encontramos el mejor vencimiento para esta tradingClass, ignoramos los demas paquetes
        if (tickerToBestExpiration.containsKey(tradingClass)) {
            return;
        }

        // Buscamos la fecha más cercana > 48 horas
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
            // Ahora solo imprimira UNA VEZ por ticker
            System.out.println("📅 Vencimiento optimo detectado para " + tradingClass + ": " + bestDate);
        }
    }

    public boolean placeOrder(String ticker, String action, int qtyIgnored, double entryPrice, double tp, double sl, String strategyName) {
        try {
            // Espera máximo 10 segundos a que la TWS responda el ID inicial
            if (!initialSync.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                System.err.println("❌ Error: TWS no sincronizó IDs a tiempo.");
                return false;
            }
        } catch (InterruptedException e) { return false; }

        Integer conId = tickerToConId.get(ticker);
        String expiration = tickerToBestExpiration.get(ticker);

        if (conId == null || expiration == null) return false;

        // Generamos IDs en secuencia atómica para todo el bracket
        int parentId = nextId.getAndIncrement();
        int tpId = nextId.getAndIncrement();
        int slId = nextId.getAndIncrement();
        Integer conId = tickerToConId.get(ticker);
        String expiration = tickerToBestExpiration.get(ticker);

        if (conId == null || expiration == null) {
            // No imprimas error aquí para no ensuciar, el engine lo manejará
            return false;
        }

        Contract contract = new Contract();
        contract.symbol(ticker);
        contract.secType("OPT");
        contract.exchange("SMART");
        contract.currency("USD");
        contract.multiplier("100");
        contract.lastTradeDateOrContractMonth(expiration);
        contract.strike(Math.round(entryPrice));
        boolean isCall = strategyName.contains("CALL");
        contract.right(isCall ? "C" : "P");
        contract.tradingClass(ticker);

        int quantity = 10;
        String ocaGroup = "OCA_" + parentId;

        Order parent = new Order();
        parent.orderId(parentId);
        parent.action("BUY");
        parent.orderType("MKT");
        parent.totalQuantity(Decimal.get(quantity));
        parent.transmit(false);
        parent.conditions().add(createPriceCond(conId, entryPrice, isCall));

        Order takeProfit = createOptionExit(parentId, quantity, ocaGroup, conId, tp, isCall);
        Order stopLoss = createOptionExit(parentId, quantity, ocaGroup, conId, sl, !isCall);
        stopLoss.transmit(true);

        client.placeOrder(parentId, contract, parent);
        client.placeOrder(tpId, contract, takeProfit);
        client.placeOrder(slId, contract, stopLoss);

        System.out.printf("🎯 OPTION SENT: 10x %s %s Strike %.0f | Exp %s | Trigger: %.2f%n",
                ticker, contract.right(), contract.strike(), expiration, entryPrice);
        return true;
    }

    private Order createOptionExit(int parentId, int qty, String oca, int conId, double price, boolean isMore) {
        Order o = new Order();
        o.orderId(nextId.getAndIncrement());
        o.parentId(parentId);
        o.action("SELL");
        o.orderType("MKT");
        o.totalQuantity(Decimal.get(qty));
        o.ocaGroup(oca);
        o.ocaType(1);
        o.transmit(false);

        o.conditions().add(createPriceCond(conId, price, isMore));
        TimeCondition tc = createTimeCond("21:55:00");
        tc.conjunctionConnection(false); // OR logic
        o.conditions().add(tc);

        return o;
    }

    private PriceCondition createPriceCond(int conId, double price, boolean isMore) {
        PriceCondition p = (PriceCondition) OrderCondition.create(OrderConditionType.Price);
        p.conId(conId);
        p.exchange("SMART");
        p.isMore(isMore);
        p.price(Math.round(price * 100.0) / 100.0);
        p.triggerMethod(2);
        return p;
    }

    private TimeCondition createTimeCond(String timeHms) {
        TimeCondition tc = (TimeCondition) OrderCondition.create(OrderConditionType.Time);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        tc.time(today + "-" + timeHms);
        tc.isMore(true);
        return tc;
    }

    @Override
    public void historicalData(int reqId, com.ib.client.Bar bar) {
        String key = reqToTicker.get(reqId);
        BarSeries series = marketData.get(key);
        if (series != null) {
            try {
                String timeStr = bar.time();
                ZonedDateTime time;

                // Handle both numerical and string date formats from IBKR
                if (timeStr.contains(" ")) {
                    String cleaned = timeStr.replaceAll("\\s+", " ");
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss z");
                    time = ZonedDateTime.parse(cleaned, fmt).withZoneSameInstant(ZoneId.of("Europe/Madrid"));
                } else {
                    time = ZonedDateTime.ofInstant(Instant.ofEpochSecond(Long.parseLong(timeStr)), ZoneId.of("Europe/Madrid"));
                }

                // Ta4j Filter: Prevent overlapping bars already in the series
                if (series.getBarCount() > 0 && !time.isAfter(series.getLastBar().getEndTime())) {
                    return;
                }

                series.addBar(time, bar.open(), bar.high(), bar.low(), bar.close(), bar.volume().value().doubleValue());
            } catch (Exception e) {
                System.err.println("⚠️ Skipping bar due to chronological conflict [" + bar.time() + "]: " + e.getMessage());
            }
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

    @Override
    public void nextValidId(int orderId) {
        nextId.set(orderId);
        initialSync.countDown(); // Desbloquea el sistema
        System.out.println("🆔 IDs sincronizados con TWS. Iniciando en: " + orderId);
    }

    / --- CALLBACK DE EJECUCIÓN ---
    @Override
    public void execDetails(int reqId, Contract contract, Execution execution) {
        // Guardamos la ejecución temporalmente esperando el reporte de comisión
        pendingExecutions.put(execution.execId(), execution);

        // Si la comisión tarda en llegar, al menos logueamos el precio ya
        System.out.printf("🔔 Fill detectado: %s %s a %.4f%n",
                contract.symbol(), execution.side(), execution.avgPrice());
    }

    // --- CALLBACK DE COMISIÓN (El cierre del círculo) ---
    @Override
    public void commissionReport(CommissionReport report) {
        Execution exec = pendingExecutions.remove(report.execId());

        if (exec != null) {
            // Obtenemos el ticker (puedes guardarlo en un mapa si reqId no es suficiente)
            // Por simplicidad en este paso, usamos el conId para identificar o lo pasamos desde el mapa

            ForensicLogger.logExecution(
                    "ID:" + exec.conid(), // O el ticker si lo mapeaste
                    exec.side(),
                    exec.avgPrice(),
                    exec.cumQty().value().doubleValue(),
                    report.commission(),
                    report.execId()
            );
        }
    }
}