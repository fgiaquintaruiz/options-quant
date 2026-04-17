package com.fgiaquinta.optionsquant.service;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.infrastructure.IbkrCallbackHandler;
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
    private final Map<Integer, String> orderIdToTicker = new ConcurrentHashMap<>();
    private final Map<Integer, Contract> activeContracts = new ConcurrentHashMap<>();
    private final Map<Integer, Order> activeOrders = new ConcurrentHashMap<>();

    // Callback tracking
    private final Map<Integer, String> requestTracker = new ConcurrentHashMap<>();
    private final Set<Integer> pendingMetadataRequests = ConcurrentHashMap.newKeySet();

    public OrderExecutionService(IbkrProperties ibkrProperties) {
        this.ibkrProperties = ibkrProperties;
        this.signal = new EJavaSignal();

        EWrapper wrapper = new DefaultEWrapper() {
            @Override
            public void connectAck() {
                if (client.isAsyncEConnect()) client.startAPI();
            }

            @Override
            public void nextValidId(int orderId) {
                log.info("IBKR Order Execution ready. Next valid order ID: {}", orderId);
                nextOrderId.set(orderId);
                connectionLatch.countDown();
            }

            @Override
            public void contractDetails(int reqId, ContractDetails details) {
                String ticker = details.contract().symbol();
                int conId = details.contract().conid();
                tickerToUnderlyingConId.put(ticker, conId);
                log.debug("Contract details: {} conId={}", ticker, conId);

                // Request option parameters
                int optReqId = nextOrderId.getAndIncrement();
                requestTracker.put(optReqId, "OptionParams: " + ticker);
                pendingMetadataRequests.add(optReqId);
                client.reqSecDefOptParams(optReqId, ticker, "", "STK", conId);
            }

            @Override
            public void contractDetailsEnd(int reqId) {
                pendingMetadataRequests.remove(reqId);
            }

            @Override
            public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId,
                                                             String tradingClass, String multiplier,
                                                             Set<String> expirations, Set<Double> strikes) {
                if (!"SMART".equals(exchange)) return;

                String ticker = tickerToUnderlyingConId.entrySet().stream()
                        .filter(e -> e.getValue() == underlyingConId)
                        .map(Map.Entry::getKey).findFirst().orElse(null);

                if (ticker != null && expirations != null && !expirations.isEmpty()) {
                    // Find the closest weekly expiration at least 48 hours away
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
                        
                        // Prioritize trading class that matches symbol if possible (standard class)
                        String currentTradingClass = tickerToTradingClass.get(ticker);
                        if (currentTradingClass == null || tradingClass.equals(ticker)) {
                            tickerToTradingClass.put(ticker, tradingClass);
                        }

                        log.info("📅 Option chain loaded for {}: expiry={}, strikes={}, tradingClass={}",
                                ticker, bestExpiration, strikes.size(), tradingClass);
                    }
                }
            }

            @Override
            public void securityDefinitionOptionalParameterEnd(int reqId) {
                pendingMetadataRequests.remove(reqId);
            }

            @Override
            public void orderStatus(int orderId, String status, Decimal filled, Decimal remaining,
                                    double avgFillPrice, long permId, int parentId, double lastFillPrice,
                                    int clientId, String whyHeld, double mktCapPrice) {
                String ticker = orderIdToTicker.get(orderId);
                log.info("📊 Order status: orderId={}, ticker={}, status={}, lastFill={}",
                        orderId, ticker, status, lastFillPrice);

                if ("Filled".equalsIgnoreCase(status) && parentId != 0 && ticker != null) {
                    log.info("✅ Exit filled for {} at ${}", ticker, lastFillPrice);
                    cleanupOrderId(orderId, ticker);
                }
            }

            @Override
            public void execDetails(int reqId, Contract contract, Execution execution) {
                String ticker = contract.symbol();
                String side = execution.side();
                double price = execution.price();
                int shares = execution.shares().value().intValue();
                log.info("✅ EXECUTION: {} {} contracts {} @ ${}", side, shares, ticker, price);
            }

            @Override
            public void error(int id, long timestamp, int errorCode, String errorMsg, String advancedOrderRejectJson) {
                if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) return;
                String context = requestTracker.getOrDefault(id, "Order");
                log.error("❌ IBKR {} Error: code={}, message={}", context, errorCode, errorMsg);
            }
        };

        this.client = new EClientSocket(wrapper, signal);
    }

    /**
     * Connects to TWS if not already connected.
     */
    public void connect() {
        if (client.isConnected()) {
            log.debug("Already connected to IBKR");
            return;
        }

        log.info("Connecting to IBKR for order execution at {}:{}", ibkrProperties.host(), ibkrProperties.port());
        client.eConnect(ibkrProperties.host(), ibkrProperties.port(), 2);

        if (!client.isConnected()) {
            throw new IllegalStateException("Failed to connect to TWS at " + ibkrProperties.host() + ":" + ibkrProperties.port());
        }

        // Start EReader
        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try { reader.processMsgs(); } catch (Exception e) { log.error("Error processing IBKR messages", e); }
            }
        }, "order-execution-ereader").start();

        try {
            if (!connectionLatch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Connection timeout");
            }
            log.info("Successfully connected to IBKR for order execution");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Connection interrupted", e);
        }
    }

    /**
     * Resolves option chain data for a ticker.
     * Returns the nearest valid expiration date.
     */
    public OptionChainResult resolveOptionChain(String ticker) {
        connect();

        // Check if we already have the data
        String existingExpiry = tickerToBestExpiration.get(ticker);
        if (existingExpiry != null) {
            return new OptionChainResult(existingExpiry, tickerToValidStrikes.getOrDefault(ticker, Set.of()), tickerToTradingClass.get(ticker));
        }

        // Request contract details
        int reqId = nextOrderId.getAndIncrement();
        requestTracker.put(reqId, "Metadata: " + ticker);
        pendingMetadataRequests.add(reqId);

        Contract stockContract = ContractFactory.createStockContract(ticker);
        log.info("Requesting contract details for {}", ticker);
        client.reqContractDetails(reqId, stockContract);

        // Wait for option chain data (up to 15 seconds)
        long startTime = System.currentTimeMillis();
        while (pendingMetadataRequests.contains(reqId) || tickerToBestExpiration.get(ticker) == null) {
            try {
                Thread.sleep(100);
                if (System.currentTimeMillis() - startTime > 15000) {
                    log.warn("Timeout waiting for option chain data for {}", ticker);
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        String expiry = tickerToBestExpiration.get(ticker);
        Set<Double> strikes = tickerToValidStrikes.getOrDefault(ticker, Set.of());
        String tClass = tickerToTradingClass.get(ticker);

        if (expiry == null) {
            throw new RuntimeException("Failed to resolve option chain for " + ticker);
        }

        return new OptionChainResult(expiry, strikes, tClass);
    }

    /**
     * Finds the best strike price for a given target.
     * Returns the closest available strike.
     */
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

    /**
     * Places a bracket order for an option contract.
     *
     * @param ticker Stock symbol
     * @param isCall True for CALL, false for PUT
     * @param qty Number of contracts
     * @param tradePlan Trade plan with entry/TP/SL
     * @param strategyName Strategy that triggered this signal
     * @return Order IDs [parentId, tpId, slId] or null if failed
     */
    public OrderResult placeOptionBracket(String ticker, boolean isCall, int qty, TradePlan tradePlan, String strategyName) {
        connect();

        // 1. Resolve option chain
        OptionChainResult chain = resolveOptionChain(ticker);
        String expiration = chain.expiration();
        String tradingClass = chain.tradingClass();
        double bestStrike = findBestStrike(ticker, tradePlan.entryPrice);

        // 2. Get underlying conId for price triggers
        Integer underlyingConId = tickerToUnderlyingConId.get(ticker);
        if (underlyingConId == null) {
            log.error("No underlying conId found for {}", ticker);
            return null;
        }

        // 3. Determine exchange for price triggers
        String triggerExchange = "ISLAND";
        Contract stockDef = ContractFactory.createStockContract(ticker);
        if (stockDef.primaryExch() != null && !stockDef.primaryExch().isEmpty()) {
            triggerExchange = stockDef.primaryExch();
        }

        // 4. Build option contract
        String right = isCall ? "C" : "P";
        Contract optionContract = ContractFactory.createOptionContract(ticker, expiration, bestStrike, right, tradingClass);

        // 5. Generate bracket order IDs
        int pId = nextOrderId.getAndIncrement();
        int tpId = nextOrderId.getAndIncrement();
        int slId = nextOrderId.getAndIncrement();

        requestTracker.put(pId, "Parent: " + ticker + " " + (isCall ? "CALL" : "PUT"));
        requestTracker.put(tpId, "TP: " + ticker);
        requestTracker.put(slId, "SL: " + ticker);

        // 6. Create bracket orders
        List<Order> bracket = OrderFactory.createOptionBracket(
                pId, tpId, slId, qty, underlyingConId, triggerExchange,
                tradePlan.entryPrice, tradePlan.takeProfit, tradePlan.stopLoss, isCall
        );

        // 7. Send orders
        log.info("🚀 Placing bracket for {} {} strike {} exp {} | Entry={} TP={} SL={} | Qty={}",
                ticker, (isCall ? "CALL" : "PUT"), bestStrike, expiration,
                tradePlan.entryPrice, tradePlan.takeProfit, tradePlan.stopLoss, qty);

        for (Order order : bracket) {
            orderIdToTicker.put(order.orderId(), ticker);
            activeContracts.put(order.orderId(), optionContract);
            activeOrders.put(order.orderId(), order);
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

    private void cleanupOrderId(int orderId, String ticker) {
        orderIdToTicker.remove(orderId);
        activeContracts.remove(orderId);
        activeOrders.remove(orderId);
    }

    @jakarta.annotation.PreDestroy
    public void disconnect() {
        if (client.isConnected()) {
            client.eDisconnect();
            log.info("Disconnected from IBKR order execution");
        }
    }

    // ===== Response Records =====

    public record OptionChainResult(String expiration, Set<Double> validStrikes, String tradingClass) {}

    public record OrderResult(int parentId, int tpOrderId, int slOrderId, double strike, String expiration, String right) {}
}
