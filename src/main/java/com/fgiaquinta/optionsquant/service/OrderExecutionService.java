package com.fgiaquinta.optionsquant.service;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.infrastructure.IbkrCallbackHandler;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.trading.ContractFactory;
import com.fgiaquinta.optionsquant.trading.OrderFactory;
import jakarta.annotation.PreDestroy;
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

    /** Overridden by {@link OrderExecutionServiceTest} to avoid multi-second sleeps. */
    static volatile long connectLatchWaitMs = 5000;
    static volatile long resolveContractWaitMs = 5000;
    static volatile long resolveChainWaitMs = 10000;

    private final IbkrProperties ibkrProperties;
    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AtomicInteger nextOrderId = new AtomicInteger(1);
    /** New latch for every connect attempt — {@link CountDownLatch} is single-use. */
    private volatile CountDownLatch connectionLatch = new CountDownLatch(1);

    // Option chain data
    private final Map<String, Integer> tickerToUnderlyingConId = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToBestExpiration = new ConcurrentHashMap<>();
    private final Map<String, String> tickerToTradingClass = new ConcurrentHashMap<>();
    private final Map<String, Set<Double>> tickerToValidStrikes = new ConcurrentHashMap<>();
    private final Map<Integer, String> requestTracker = new ConcurrentHashMap<>();

    // Bracket state for position closure
    private final ConcurrentHashMap<Integer, BracketTradeInfo> bracketStateMap = new ConcurrentHashMap<>();

    // Callback tracking
    private final Set<Integer> pendingMetadataRequests = ConcurrentHashMap.newKeySet();

    @org.springframework.beans.factory.annotation.Autowired
    public OrderExecutionService(IbkrProperties ibkrProperties) {
        this.ibkrProperties = ibkrProperties;
        this.signal = new EJavaSignal();
        this.client = new EClientSocket(createWrapper(this), this.signal);
    }

    protected OrderExecutionService(IbkrProperties ibkrProperties, EClientSocket client) {
        this.ibkrProperties = ibkrProperties;
        this.client = client;
        this.signal = new EJavaSignal();
    }

    private static EWrapper createWrapper(OrderExecutionService service) {
        return new DefaultEWrapper() {
            @Override
            public void nextValidId(int orderId) {
                service.log.info("OrderExecutionService connected. Next ID: {}", orderId);
                service.nextOrderId.set(orderId);
                service.connectionLatch.countDown();
            }

            @Override
            public void contractDetails(int reqId, ContractDetails contractDetails) {
                String ticker = service.requestTracker.get(reqId);
                if (ticker != null) {
                    int conId = contractDetails.contract().conid();
                    if (conId > 0) {
                        service.tickerToUnderlyingConId.put(ticker, conId);
                        service.log.info("🎯 Resolved underlying conId for {}: {}", ticker, conId);
                    } else {
                        service.log.warn("Contract details for {} returned conId=0 (reqId={})", ticker, reqId);
                    }
                }
            }

            @Override
            public void contractDetailsEnd(int reqId) {
                service.pendingMetadataRequests.remove(reqId);
            }

            @Override
            public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {
                String ticker = service.requestTracker.get(reqId);
                if (ticker != null) {
                    service.tickerToUnderlyingConId.put(ticker, underlyingConId);
                    
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd");
                    String minAllowedDate = LocalDate.now().plusDays(2).format(fmt);

                    List<String> validExps = expirations.stream()
                            .filter(exp -> exp.compareTo(minAllowedDate) >= 0)
                            .sorted()
                            .toList();

                    if (!validExps.isEmpty()) {
                        String bestExpiration = validExps.get(0);
                        service.tickerToBestExpiration.put(ticker, bestExpiration);
                        service.tickerToValidStrikes.put(ticker, strikes);
                        
                        String currentTradingClass = service.tickerToTradingClass.get(ticker);
                        if (currentTradingClass == null || tradingClass.equals(ticker)) {
                            service.tickerToTradingClass.put(ticker, tradingClass);
                        }

                        service.log.info("📅 Option chain loaded for {}: expiry={}, strikes={}, tradingClass={}",
                                 ticker, bestExpiration, strikes.size(), tradingClass);
                    }
                    service.pendingMetadataRequests.remove(reqId);
                }
            }

            @Override
            public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;
                
                String context = service.requestTracker.getOrDefault(id, "Request " + id);
                service.log.error("❌ IBKR {} Error: code={}, message={}", context, errorCode, errorMsg);
                
                if (service.pendingMetadataRequests.contains(id)) {
                    service.pendingMetadataRequests.remove(id);
                }
            }
        };
    }


    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public synchronized void connect() {
        if (client.isConnected()) {
            return;
        }
        try {
            client.eDisconnect();
        } catch (Exception ignored) {
            // Free client id on TWS after devtools restart or half-open socket
        }

        connectionLatch = new CountDownLatch(1);

        int clientId = ibkrProperties.orderExecutionClientId();
        log.info("Connecting to IBKR for order execution at {}:{} (clientId={})", ibkrProperties.host(), ibkrProperties.port(), clientId);
        client.eConnect(ibkrProperties.host(), ibkrProperties.port(), clientId);

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
            if (!connectionLatch.await(connectLatchWaitMs, TimeUnit.MILLISECONDS)) {
                log.warn("Timed out waiting for nextValidId from IBKR");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void disconnect() {
        try {
            if (client != null && client.isConnected()) {
                client.eDisconnect();
            }
        } catch (Exception e) {
            log.debug("OrderExecutionService disconnect: {}", e.getMessage());
        }
    }

    public OptionChainResult resolveOptionChain(String ticker) {
        connect();

        String existingExpiry = tickerToBestExpiration.get(ticker);
        if (existingExpiry != null) {
            return new OptionChainResult(existingExpiry, tickerToValidStrikes.getOrDefault(ticker, Set.of()), tickerToTradingClass.get(ticker));
        }

        // Step 1: Resolve underlying conId if missing
        if (!tickerToUnderlyingConId.containsKey(ticker)) {
            int reqId = nextOrderId.getAndIncrement();
            requestTracker.put(reqId, ticker);
            pendingMetadataRequests.add(reqId);
            
            log.info("🔍 Resolving underlying conId for {}...", ticker);
            client.reqContractDetails(reqId, IbkrCallbackHandler.createStockContract(ticker));
            
            long start = System.currentTimeMillis();
            while (pendingMetadataRequests.contains(reqId) && (System.currentTimeMillis() - start) < resolveContractWaitMs) {
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        int underlyingId = tickerToUnderlyingConId.getOrDefault(ticker, 0);
        if (underlyingId <= 0) {
            // conId resolution via reqContractDetails can be flaky for some tickers.
            // Fall back to calling reqSecDefOptParams with conId=0 — IBKR still returns
            // the option chain via securityDefinitionOptionalParameter, which populates
            // the conId as a side effect. This is the behavior that worked historically.
            log.warn("⚠️ conId not resolved for {} via reqContractDetails, falling back to reqSecDefOptParams with conId=0", ticker);
        }

        // Step 2: Resolve option parameters
        int reqId = nextOrderId.getAndIncrement();
        requestTracker.put(reqId, ticker);
        pendingMetadataRequests.add(reqId);

        log.info("🔍 Requesting option chain for {} (reqId={}, conId={})", ticker, reqId, underlyingId);
        client.reqSecDefOptParams(reqId, ticker, "", "STK", underlyingId);

        long start = System.currentTimeMillis();
        while (pendingMetadataRequests.contains(reqId) && (System.currentTimeMillis() - start) < resolveChainWaitMs) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        if (pendingMetadataRequests.contains(reqId)) {
            log.warn("Timeout waiting for option chain data for {}", ticker);
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

        // Persist bracket state for later position closure
        bracketStateMap.put(pId, new BracketTradeInfo(optionContract, qty, tradePlan.entryPrice, ZonedDateTime.now()));

        log.info("✅ Bracket sent! Parent={} TP={} SL={}", pId, tpId, slId);
        return new OrderResult(pId, tpId, slId, bestStrike, expiration, right);
    }

    public void cancelOrder(int orderId) {
        connect();
        log.info("🚫 Cancelling order ID: {}", orderId);
        client.cancelOrder(orderId, new OrderCancel());
    }

    /**
     * Closes a position by cancelling TP/SL conditional orders and placing a market sell.
     * Uses stored bracket state to retrieve contract and quantity.
     *
     * @param parentOrderId The parent order ID (entry order)
     * @param tpOrderId The take-profit order ID
     * @param slOrderId The stop-loss order ID
     */
    public void closePositionViaConditions(int parentOrderId, int tpOrderId, int slOrderId) {
        connect();

        log.info("🎯 Closing position: parentId={}, tpId={}, slId={}", parentOrderId, tpOrderId, slOrderId);

        // Cancel both conditional orders first
        log.info("Cancelling conditional orders: TP={}, SL={}", tpOrderId, slOrderId);
        client.cancelOrder(tpOrderId, new OrderCancel());
        client.cancelOrder(slOrderId, new OrderCancel());

        // Retrieve stored bracket state
        BracketTradeInfo info = bracketStateMap.remove(parentOrderId);
        if (info == null) {
            log.error("No bracket state found for parentId={} - cannot close position", parentOrderId);
            return;
        }

        // Place market sell order
        int sellOrderId = nextOrderId.getAndIncrement();
        Order marketSell = OrderFactory.createMarketOrder(sellOrderId, "SELL", info.quantity());

        log.info("📤 Placing market sell: orderId={}, contract={}, qty={}",
                sellOrderId, info.contract().symbol(), info.quantity());

        try {
            client.placeOrder(sellOrderId, info.contract(), marketSell);
            log.info("✅ Market sell order placed successfully: orderId={}", sellOrderId);
        } catch (Exception e) {
            log.error("❌ Failed to place market sell order: {}", e.getMessage(), e);
        }
    }

    public record OptionChainResult(String expiration, Set<Double> validStrikes, String tradingClass) {}

    public record OrderResult(int parentId, int tpOrderId, int slOrderId, double strike, String expiration, String right) {}

    /**
     * Stores bracket trade info for later position closure.
     */
    public record BracketTradeInfo(Contract contract, int quantity, double entryPrice, ZonedDateTime entryTime) {}
}
