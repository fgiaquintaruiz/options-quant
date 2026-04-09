package com.fgiaquinta.optionsquant.service;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.infrastructure.IbkrCallbackHandler;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core service for IBKR historical data downloads.
 * Uses composition with IbkrCallbackHandler instead of inheriting from DefaultEWrapper.
 */
@Slf4j
@Service
public class IbkrService {

    private final IbkrProperties properties;
    private final IbkrCallbackHandler callbackHandler;
    private final MetricsService metrics;
    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AtomicInteger nextOrderId = new AtomicInteger();
    private final CountDownLatch connectionLatch = new CountDownLatch(1);

    // Request tracking
    private final Map<Integer, String> requestTickerMap = new ConcurrentHashMap<>();
    private final Map<Integer, TimeFrame> requestTimeframeMap = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> receivedData = new ConcurrentHashMap<>();
    private final Set<Integer> pendingRequests = ConcurrentHashMap.newKeySet();

    public IbkrService(IbkrProperties properties, MetricsService metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.signal = new EJavaSignal();
        this.callbackHandler = new IbkrCallbackHandler(
                this::onBarReceived,
                this::onRequestComplete,
                this::onErrorReceived,
                this::onConnectionReady
        );
        this.client = new EClientSocket(callbackHandler, signal);
    }

    /**
     * Connect to TWS or IB Gateway.
     */
    public void connect() {
        log.info(">>> connect() - Connecting to IBKR at {}:{} (clientId={})", properties.host(), properties.port(), nextOrderId.get());
        long startTime = System.currentTimeMillis();

        client.eConnect(properties.host(), properties.port(), nextOrderId.get() + 1);

        if (!client.isConnected()) {
            log.error("<<< connect() - FAILED to connect to TWS at {}:{}", properties.host(), properties.port());
            metrics.incrementIbkrError("connection_failed");
            throw new IllegalStateException("Failed to connect to TWS at " + properties.host() + ":" + properties.port());
        }

        // Start EReader thread
        final EReader reader = new EReader(client, signal);
        reader.start();
        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    log.error("Error processing IBKR messages", e);
                }
            }
        }, "ibkr-ereader").start();

        // Wait for connection
        try {
            if (!connectionLatch.await(properties.syncTimeout(), TimeUnit.SECONDS)) {
                log.error("<<< connect() - TIMEOUT after {} seconds", properties.syncTimeout());
                metrics.incrementIbkrError("connection_timeout");
                throw new IllegalStateException("Connection timeout after " + properties.syncTimeout() + " seconds");
            }
            long elapsed = System.currentTimeMillis() - startTime;
            log.info("<<< connect() - Successfully connected in {}ms", elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("<<< connect() - Interrupted while waiting for connection");
            throw new IllegalStateException("Connection interrupted", e);
        }
    }

    /**
     * Disconnect from TWS.
     */
    public void disconnect() {
        log.info(">>> disconnect() - Disconnecting from IBKR");
        if (client.isConnected()) {
            client.eDisconnect();
            log.info("<<< disconnect() - Disconnected from IBKR");
        } else {
            log.debug("<<< disconnect() - Already disconnected");
        }
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /**
     * Download historical data for a single ticker and timeframe.
     */
    public List<Candle> downloadHistoricalData(String ticker, TimeFrame timeframe) {
        log.info(">>> downloadHistoricalData(ticker={}, timeframe={})", ticker, timeframe);
        long startTime = System.currentTimeMillis();

        try {
            return metrics.timeIbkrCall("downloadHistoricalData", () -> {
                // Auto-connect if not already connected
                if (!isConnected()) {
                    log.info("Not connected to IBKR, auto-connecting...");
                    connect();
                }

                String cacheKey = timeframe.toCacheKey(ticker);
                List<Candle> candles = Collections.synchronizedList(new ArrayList<>());
                receivedData.put(cacheKey, candles);

                int reqId = nextOrderId.getAndIncrement();
                registerRequest(reqId, ticker, timeframe);

                Contract contract = IbkrCallbackHandler.createStockContract(ticker);

                log.info("    Sending reqHistoricalData: reqId={}, duration={}, barSize={}, whatToShow=TRADES",
                        reqId, timeframe.getIbkrDuration(), timeframe.getIbkrBarSize());

                client.reqHistoricalData(
                        reqId,
                        contract,
                        "",
                        timeframe.getIbkrDuration(),
                        timeframe.getIbkrBarSize(),
                        "TRADES",
                        1,
                        1,
                        false,
                        null
                );

                waitForRequestCompletion(reqId, ticker, timeframe);
                cleanupRequest(reqId);

                long elapsed = System.currentTimeMillis() - startTime;
                log.info("<<< downloadHistoricalData(ticker={}, timeframe={}) - {} candles in {}ms",
                        ticker, timeframe, candles.size(), elapsed);

                if (candles.isEmpty()) {
                    metrics.incrementIbkrError("no_data_returned");
                }

                return new ArrayList<>(candles);
            });
        } catch (Exception e) {
            log.error("Error during downloadHistoricalData(ticker={}, timeframe={}): {}", ticker, timeframe, e.getMessage());
            metrics.incrementIbkrError("download_exception");
            throw new RuntimeException("Failed to download historical data for " + ticker + " [" + timeframe + "]", e);
        }
    }

    /**
     * Download all timeframes for a single ticker.
     */
    public Map<TimeFrame, List<Candle>> downloadAllTimeframes(String ticker) {
        log.info(">>> downloadAllTimeframes(ticker={})", ticker);
        long startTime = System.currentTimeMillis();

        // Auto-connect if not already connected
        if (!isConnected()) {
            log.info("Not connected to IBKR, auto-connecting...");
            connect();
        }

        Map<TimeFrame, List<Candle>> results = new LinkedHashMap<>();

        for (TimeFrame tf : TimeFrame.values()) {
            try {
                log.info("    Downloading timeframe: {}", tf.name());
                List<Candle> candles = downloadHistoricalData(ticker, tf);
                results.put(tf, candles);
                log.info("    Timeframe {} complete: {} candles", tf.name(), candles.size());
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("    Failed to download {} data for {}: {}", tf, ticker, e.getMessage());
                metrics.incrementIbkrError("timeframe_download_failed");
                results.put(tf, Collections.emptyList());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        int totalCandles = results.values().stream().mapToInt(List::size).sum();
        log.info("<<< downloadAllTimeframes(ticker={}) - {} total candles across {} timeframes in {}ms",
                ticker, totalCandles, results.size(), elapsed);

        return results;
    }

    // ===== Callback handlers (private) =====

    private void onBarReceived(IbkrCallbackHandler.BarEvent event) {
        String ticker = requestTickerMap.get(event.reqId());
        TimeFrame tf = requestTimeframeMap.get(event.reqId());
        if (ticker != null && tf != null) {
            String key = tf.toCacheKey(ticker);
            List<Candle> candles = receivedData.get(key);
            if (candles != null) {
                candles.add(event.candle());
            }
        }
    }

    private void onRequestComplete(IbkrCallbackHandler.RequestCompleteEvent event) {
        log.debug("    Request {} completed", event.reqId());
        pendingRequests.remove(event.reqId());
    }

    private void onErrorReceived(IbkrCallbackHandler.ErrorEvent event) {
        log.warn("    Error received: id={}, code={}, message={}", event.id(), event.code(), event.message());
        if (pendingRequests.contains(event.id())) {
            log.warn("    Removing failed request {} from pending list (error {})", event.id(), event.code());
            pendingRequests.remove(event.id());
            metrics.incrementIbkrError("error_" + event.code());
        }
    }

    private void onConnectionReady() {
        log.info("    Connection ready, nextOrderId set to {}", nextOrderId.get());
        nextOrderId.set(Math.max(nextOrderId.get(), 1));
        connectionLatch.countDown();
    }

    // ===== Private helpers =====

    private void registerRequest(int reqId, String ticker, TimeFrame timeframe) {
        requestTickerMap.put(reqId, ticker);
        requestTimeframeMap.put(reqId, timeframe);
        pendingRequests.add(reqId);
        log.debug("    Registered request: reqId={}, ticker={}, timeframe={}", reqId, ticker, timeframe);
    }

    private void waitForRequestCompletion(int reqId, String ticker, TimeFrame timeframe) {
        int timeoutSeconds = 120;
        long startTime = System.currentTimeMillis();

        while (pendingRequests.contains(reqId)) {
            try {
                Thread.sleep(100);
                if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000L) {
                    log.warn("    TIMEOUT waiting for reqId={} (ticker={}, timeframe={}) after {}s",
                            reqId, ticker, timeframe, timeoutSeconds);
                    metrics.incrementIbkrError("timeout");
                    break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("    Interrupted while waiting for reqId={}", reqId);
                break;
            }
        }

        log.debug("    Cancelling historical data for reqId={}", reqId);
        client.cancelHistoricalData(reqId);
    }

    private void cleanupRequest(int reqId) {
        requestTickerMap.remove(reqId);
        requestTimeframeMap.remove(reqId);
        pendingRequests.remove(reqId);
    }
}
