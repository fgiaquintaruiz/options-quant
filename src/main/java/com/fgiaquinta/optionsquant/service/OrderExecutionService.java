package com.fgiaquinta.optionsquant.service;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.trading.ContractFactory;
import com.fgiaquinta.optionsquant.trading.OrderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles option order execution: contract resolution, option chain lookup, bracket order placement.
 * Connects to TWS, finds the right option contract, and places orders.
 */
@Slf4j
@Service
public class OrderExecutionService {

    private final IbkrProperties ibkrProperties;
    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AtomicInteger nextOrderId = new AtomicInteger(1);
    private final CountDownLatch connectionLatch = new CountDownLatch(1);

    // Option chain data
    private final Map<String, Integer> tickerToUnderlyingConId = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToTradingClass = new ConcurrentHashMap<>();
    private final Map<String, Set<Double>> tickerToValidStrikes = new ConcurrentHashMap<>();
    private final Map<Integer, String> requestTracker = new ConcurrentHashMap<>();

    // Callback tracking
    private final Set<Integer> pendingMetadataRequests = ConcurrentHashMap.newKeySet();

    public OrderExecutionService(IbkrProperties ibkrProperties) {
        this.ibkrProperties = ibkrProperties;
        this.signal = new EJavaSignal();

        EWrapper wrapper = new DefaultEWrapper() {
            @Override
            public void nextValidId(int orderId) {
                log.info("OrderExecutionService connected. Next ID: {}", orderId);
                nextOrderId.set(orderId);
                connectionLatch.countDown();
            }

            @Override
            public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
                String ticker = requestTracker.get(reqId);
                if (ticker != null) {
                    tickerToUnderlyingConId.put(ticker, underlyingConId);
                    
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
                    String minAllowedDate = LocalDate.now().plusDays(2).format(fmt);

                    List<String> validExps = expirations.stream()
                            .filter(exp -> exp.compareTo(minAllowedDate) >= 0)
                            .sorted()
                            .toList();

                    if (!validExps.isEmpty()) {
                        String bestExpiration = validExps.get(0);
                        tickerToBestExpiration.put(ticker, bestExpiration);
                        tickerToValidStrikes.put(ticker, strikes);
                        
                        String currentTradingClass = tickerToTradingClass.get(ticker);
                        if (currentTradingClass == null || tradingClass.equals(ticker)) {
                            tickerToTradingClass.put(ticker, tradingClass);
                        }

                        log.info("📅 Option chain loaded for {}: expiry={}, strikes={}, tradingClass={}",
                                ticker, bestExpiration, strikes.size(), tradingClass);
                    }
                    pendingMetadataRequests.remove(reqId);
                }
            }

            @Override
            public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;
                log.error("❌ OrderExecution IBKR Error: code={}, message={}", errorCode, errorMsg);
            }
        };

        this.client = new EClientSocket(wrapper, signal);
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public void connect() {
        if (client.isConnected()) return;

        log.info("Connecting to IBKR for order execution at {}:{}", ibkrProperties.host(), ibkrProperties.port());
        client.eConnect(ibkrProperties.host(), ibkrProperties.port(), 2);

        if (!client.isConnected()) {
            throw new IllegalStateException("Failed to connect to TWS at " + ibkrProperties.host() + ":" + ibkrProperties.port());
        }

        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) { log.error("Error processing IBKR messages", e); }
            }
        }, "order-execution-ereader").start();

        try {
            if (!connectionLatch.await(5, TimeUnit.SECONDS)) {
                log.warn("Timed out waiting for nextValidId from IBKR");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public OptionChainResult resolveOptionChain(String ticker) {
        connect();

        String existingExpiry = tickerToBestExpiration.get(ticker);
        if (existingExpiry != null) {
            return new OptionChainResult(existingExpiry, tickerToValidStrikes.getOrDefault(ticker, Set.of()), tickerToTradingClass.get(ticker));
        }

        int reqId = nextOrderId.getAndIncrement();
        requestTracker.put(reqId, ticker);
        pendingMetadataRequests.add(reqId);

        log.info("🔍 Requesting option chain for {} (reqId={})", ticker, reqId);
        client.reqSecDefOptParams(reqId, ticker, "", "STK", 0);

        long start = System.currentTimeMillis();
        while (pendingMetadataRequests.contains(reqId) && (System.currentTimeMillis() - start) < 10000) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        String expiry = tickerToBestExpiration.get(ticker);
        if (expiry == null) {
            throw new RuntimeException("Failed to resolve option chain for " + ticker);
        }

        return new OptionChainResult(expiry, tickerToValidStrikes.getOrDefault(ticker, Set.of()), tickerToTradingClass.get(ticker));
    }

    public double findBestStrike(String ticker, double targetPrice) {
        Set<Double> strikes = tickerToValidStrikes.get(ticker);
        if (strikes == null || strikes.isEmpty()) {
            OptionChainResult chain = resolveOptionChain(ticker);
            strikes = chain.validStrikes();
        }

        return strikes.stream()
                .min(Comparator.comparingDouble(s -> Math.abs(s - targetPrice)))
                .orElse((double) Math.round(targetPrice));
    }

    public OrderResult placeOptionBracket(String ticker, boolean isCall, int qty, TradePlan tradePlan, String strategyName) {
        connect();

        OptionChainResult chain = resolveOptionChain(ticker);
        String expiration = chain.expiration();
        String tradingClass = chain.tradingClass();
        double bestStrike = findBestStrike(ticker, tradePlan.entryPrice);

        Integer underlyingConId = tickerToUnderlyingConId.get(ticker);
        if (underlyingConId == null) {
            log.error("No underlying conId found for {}", ticker);
            return null;
        }

        String triggerExchange = "ISLAND";
        Contract stockDef = ContractFactory.createStockContract(ticker);
        if (stockDef.primaryExch() != null && !stockDef.primaryExch().isEmpty()) {
            triggerExchange = stockDef.primaryExch();
        }

        String right = isCall ? "C" : "P";
        Contract optionContract = ContractFactory.createOptionContract(ticker, expiration, bestStrike, right, tradingClass);

        int pId = nextOrderId.getAndIncrement();
        int tpId = nextOrderId.getAndIncrement();
        int slId = nextOrderId.getAndIncrement();

        List<Order> bracket = OrderFactory.createOptionBracket(
                pId, tpId, slId, qty,
                underlyingConId, triggerExchange,
                tradePlan.entryPrice, tradePlan.takeProfit, tradePlan.stopLoss, isCall
        );

        log.info("🚀 Placing bracket for {} {} strike {} exp {} | Entry={} TP={} SL={} | Qty={}", 
                ticker, right, bestStrike, expiration, tradePlan.entryPrice, tradePlan.takeProfit, tradePlan.stopLoss, qty);

        for (Order order : bracket) {
            client.placeOrder(order.orderId(), optionContract, order);
        }

        log.info("✅ Bracket sent! Parent={} TP={} SL={}", pId, tpId, slId);
        return new OrderResult(pId, tpId, slId, bestStrike, expiration, right);
    }

    public void cancelOrder(int orderId) {
        connect();
        log.info("🚫 Cancelling order ID: {}", orderId);
        client.cancelOrder(orderId, new OrderCancel());
    }

    public record OptionChainResult(String expiration, Set<Double> validStrikes, String tradingClass) {}

    public record OrderResult(int parentId, int tpOrderId, int slOrderId, double strike, String expiration, String right) {}
}
