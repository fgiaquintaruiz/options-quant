package com.fgiaquinta.optionsquant.service;

import com.ib.client.*;
import com.fgiaquinta.optionsquant.candle.backfill.IbkrHistoricalDataException;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.infrastructure.IbkrCallbackHandler;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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

    // Separate logger for IBKR network/download operations
    private static final org.slf4j.Logger ibkrLog =
            org.slf4j.LoggerFactory.getLogger("IbkrDownload");

    private final IbkrProperties properties;
    private final IbkrCallbackHandler callbackHandler;
    private final MetricsService metrics;
    private final EClientSocket client;
    private final EJavaSignal signal;
    private final AtomicInteger nextOrderId = new AtomicInteger();
    /** Recreated on each connect so await() works after a prior successful connection. */
    private volatile CountDownLatch connectionLatch = new CountDownLatch(1);

    // Request tracking
    private final Map<Integer, String> requestTickerMap = new ConcurrentHashMap<>();
    private final Map<Integer, TimeFrame> requestTimeframeMap = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> receivedData = new ConcurrentHashMap<>();
    private final Set<Integer> pendingRequests = ConcurrentHashMap.newKeySet();
    private final Set<Integer> cancelledRequests = ConcurrentHashMap.newKeySet();  // Track intentionally cancelled requests
    /** Maps reqId → TWS error code for requests that TWS rejected with an error callback. */
    private final Map<Integer, Integer> failedRequests = new ConcurrentHashMap<>();

    @SuppressWarnings("this-escape")
    public IbkrService(IbkrProperties properties, MetricsService metrics) {
        this.properties = properties;
        this.metrics = metrics;
        this.signal = new EJavaSignal();
        this.callbackHandler = new IbkrCallbackHandler(
                this::onBarReceived,
                this::onRequestComplete,
                this::onErrorReceived,
                this::onNextValidIdReceived
        );
        this.client = new EClientSocket(callbackHandler, signal);
    }

    /**
     * Connect to TWS or IB Gateway.
     */
    public void connect() {
        if (client.isConnected()) {
            return;
        }
        connectionLatch = new CountDownLatch(1);

        int clientId = properties.historicalDataClientId();
        log.info(">>> connect() - Connecting to IBKR at {}:{} (clientId={})", properties.host(), properties.port(), clientId);
        long startTime = System.currentTimeMillis();

        client.eConnect(properties.host(), properties.port(), clientId);

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
            
            // Verify connection is actually established (not failed with error 502)
            if (!client.isConnected()) {
                log.error("<<< connect() - Connection failed (TWS not available)");
                metrics.incrementIbkrError("connection_failed");
                throw new IllegalStateException("Failed to connect to TWS at " + properties.host() + ":" + properties.port());
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
    @jakarta.annotation.PreDestroy
    public void disconnect() {
        log.info(">>> disconnect() - Disconnecting from IBKR");
        if (client.isConnected()) {
            client.eDisconnect();
            log.info("<<< disconnect() - Disconnected from IBKR");
        } else {
            log.debug("<<< disconnect() - Already disconnected");
        }
    }

    /**
     * Connect to TWS with retry logic.
     * Retries every 4 seconds if connection fails.
     */
    private void connectWithRetry() {
        int maxRetries = 3;
        int retryDelaySeconds = 4;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                connect();
                return;  // Success
            } catch (Exception e) {
                log.warn("⚠️ [IBKR] Connection attempt {}/{} failed: {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    log.info("🔄 [IBKR] Retrying connection in {} seconds...", retryDelaySeconds);
                    try {
                        Thread.sleep(retryDelaySeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Connection interrupted during retry", ie);
                    }
                } else {
                    log.error("❌ [IBKR] All {} connection attempts failed. TWS may not be running.", maxRetries);
                    throw new IllegalStateException("Failed to connect to TWS after " + maxRetries + " attempts", e);
                }
            }
        }
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    /**
     * Download historical data for a single ticker and timeframe.
     * Downloads the full duration specified in the TimeFrame config.
     */
    public List<Candle> downloadHistoricalData(String ticker, TimeFrame timeframe) {
        return downloadHistoricalData(ticker, timeframe, null);
    }

    /**
     * Download historical data for a single ticker and timeframe.
     * If endDateTime is provided, downloads only data from that point forward (delta download).
     * If endDateTime is null, downloads the full duration (full download).
     */
    public List<Candle> downloadHistoricalData(String ticker, TimeFrame timeframe, ZonedDateTime endDateTime) {
        ibkrLog.info(">>> [IBKR] downloadHistoricalData(ticker={}, timeframe={}, endDateTime={})", ticker, timeframe, endDateTime);
        long startTime = System.currentTimeMillis();

        try {
            return metrics.timeIbkrCall("downloadHistoricalData", () -> {
                // Auto-connect if not already connected (with retry logic)
                if (!isConnected()) {
                    ibkrLog.info("📥 [IBKR] Not connected to IBKR, auto-connecting...");
                    try {
                        connectWithRetry();
                    } catch (Exception e) {
                        ibkrLog.warn("⚠️ [IBKR] Connection failed, returning empty data for {} [{}]", ticker, timeframe);
                        return Collections.emptyList();
                    }
                }

                String cacheKey = timeframe.toCacheKey(ticker);
                List<Candle> candles = Collections.synchronizedList(new ArrayList<>());
                receivedData.put(cacheKey, candles);

                int reqId = nextOrderId.getAndIncrement();
                registerRequest(reqId, ticker, timeframe);

                Contract contract = IbkrCallbackHandler.createStockContract(ticker);

                // Format endDateTime for IBKR: "yyyyMMdd HH:mm:ss" or empty for full history
                String endDateTimeStr = "";
                if (endDateTime != null) {
                    // Convert to NY timezone and format for IBKR
                    ZonedDateTime nyTime = endDateTime.withZoneSameInstant(ZoneId.of("America/New_York"));
                    endDateTimeStr = nyTime.format(DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss"));
                }

                ibkrLog.info("    [IBKR] Sending reqHistoricalData: reqId={}, duration={}, barSize={}, whatToShow=TRADES, endDateTime={}",
                        reqId, timeframe.getIbkrDuration(), timeframe.getIbkrBarSize(), endDateTimeStr.isEmpty() ? "now" : endDateTimeStr);

                client.reqHistoricalData(
                        reqId,
                        contract,
                        endDateTimeStr,  // "" = now, otherwise specific datetime
                        timeframe.getIbkrDuration(),
                        timeframe.getIbkrBarSize(),
                        "TRADES",
                        2,  // 1=RTH only, 2=All sessions (includes pre/post market)
                        1,  // Format: 1=seconds epoch, 2=yyyy-MM-dd HH:mm:ss
                        false,  // Don't keep up to date for delta downloads (we'll request fresh data each time)
                        null
                );

                waitForRequestCompletion(reqId, ticker, timeframe);

                // Check if TWS signalled an error for this request (e.g. code 200: no security found)
                Integer errorCode = failedRequests.remove(reqId);
                cleanupRequest(reqId);

                if (errorCode != null) {
                    String errMsg = "TWS error " + errorCode + " for " + ticker + " [" + timeframe + "]";
                    ibkrLog.warn("<<< [IBKR] {} — throwing IbkrHistoricalDataException", errMsg);
                    metrics.incrementIbkrError("tws_rejected_" + errorCode);
                    throw new IbkrHistoricalDataException(errorCode, errMsg);
                }

                long elapsed = System.currentTimeMillis() - startTime;
                ibkrLog.info("<<< [IBKR] downloadHistoricalData(ticker={}, timeframe={}) - {} candles in {}ms",
                        ticker, timeframe, candles.size(), elapsed);

                if (candles.isEmpty()) {
                    metrics.incrementIbkrError("no_data_returned");
                }

                return new ArrayList<>(candles);
            });
        } catch (Exception e) {
            ibkrLog.error("❌ [IBKR] Error during downloadHistoricalData(ticker={}, timeframe={}): {}", ticker, timeframe, e.getMessage());
            metrics.incrementIbkrError("download_exception");
            throw new RuntimeException("Failed to download historical data for " + ticker + " [" + timeframe + "]", e);
        }
    }

    /**
     * Download all timeframes for a single ticker.
     */
    public Map<TimeFrame, List<Candle>> downloadAllTimeframes(String ticker) {
        ibkrLog.info(">>> [IBKR] downloadAllTimeframes(ticker={})", ticker);
        long startTime = System.currentTimeMillis();

        // Auto-connect if not already connected
        if (!isConnected()) {
            ibkrLog.info("📥 [IBKR] Not connected to IBKR, auto-connecting...");
            connect();
        }

        Map<TimeFrame, List<Candle>> results = new LinkedHashMap<>();

        for (TimeFrame tf : TimeFrame.values()) {
            try {
                ibkrLog.info("    [IBKR] Downloading timeframe: {}", tf.name());
                List<Candle> candles = downloadHistoricalData(ticker, tf);
                results.put(tf, candles);
                ibkrLog.info("    [IBKR] Timeframe {} complete: {} candles", tf.name(), candles.size());
                Thread.sleep(2000);
            } catch (Exception e) {
                ibkrLog.error("❌ [IBKR] Failed to download {} data for {}: {}", tf, ticker, e.getMessage());
                metrics.incrementIbkrError("timeframe_download_failed");
                results.put(tf, Collections.emptyList());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        int totalCandles = results.values().stream().mapToInt(List::size).sum();
        ibkrLog.info("<<< [IBKR] downloadAllTimeframes(ticker={}) - {} total candles across {} timeframes in {}ms",
                ticker, totalCandles, results.size(), elapsed);

        return results;
    }

    /**
     * Download only new candles (delta) for a single ticker and timeframe since the last known timestamp.
     * IBKR always returns full duration backwards from "now", so we filter client-side
     * to keep only candles NEWER than lastCandleTimestamp.
     */
    public List<Candle> downloadDelta(String ticker, TimeFrame timeframe, ZonedDateTime lastCandleTimestamp) {
        if (lastCandleTimestamp == null) {
            ibkrLog.info("📥 [IBKR] No CSV data for {} [{}] — downloading full history", ticker, timeframe);
            return downloadHistoricalData(ticker, timeframe);
        }

        ibkrLog.info("📥 [IBKR] Delta download: {} [{}] since {}", ticker, timeframe, lastCandleTimestamp);
        long startTime = System.currentTimeMillis();

        // Auto-connect if not already connected (with retry logic)
        if (!isConnected()) {
            ibkrLog.info("📥 [IBKR] Not connected to IBKR, auto-connecting...");
            try {
                connectWithRetry();
            } catch (Exception e) {
                ibkrLog.warn("⚠️ [IBKR] Connection failed, returning empty delta for {} [{}]", ticker, timeframe);
                return Collections.emptyList();
            }
        }

        try {
            // Request from "now" backwards (IBKR default behavior)
            List<Candle> allCandles = downloadHistoricalData(ticker, timeframe);

            // Filter: keep only candles NEWER than the last CSV candle
            List<Candle> deltaCandles = allCandles.stream()
                    .filter(c -> c.timestamp().isAfter(lastCandleTimestamp))
                    .toList();

            long elapsed = System.currentTimeMillis() - startTime;
            ibkrLog.info("📥 [IBKR] Delta result: {} [{}] — {} new candles (filtered from {} received) in {}ms",
                    ticker, timeframe, deltaCandles.size(), allCandles.size(), elapsed);
            return deltaCandles;
        } catch (Exception e) {
            ibkrLog.error("❌ [IBKR] Failed to download delta for {} [{}]: {}", ticker, timeframe, e.getMessage());
            metrics.incrementIbkrError("delta_download_failed");
            return Collections.emptyList();
        }
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
        // Check if this is a cancellation error for a request we intentionally cancelled
        if (event.message() != null && event.message().contains("API historical data query cancelled")) {
            if (cancelledRequests.contains(event.id())) {
                log.debug("    Ignoring expected cancellation error for intentionally cancelled request {}", event.id());
                return;  // This is expected, not an error
            }
        }

        // Error 502: Can't connect to TWS — log error but DON'T shut down
        // The app should continue running and retry connection later
        if (event.code() == 502) {
            log.error("    🔌 IBKR error 502 - Can't connect to TWS/Gateway.");
            log.error("    → Check that TWS/Gateway is running and 'Enable ActiveX and Socket Clients' is ON.");
            log.error("    → Simulated port: 7497 (TWS) / 4002 (Gateway) | Live port: 7496 (TWS) / 4001 (Gateway)");
            log.error("    → App will continue running and retry connection in 4 seconds.");
            // Don't shut down - just mark connection as failed
            disconnect();
            // Signal the connectionLatch to unblock any waiting connect() call
            connectionLatch.countDown();
            metrics.incrementIbkrError("error_" + event.code());
            return;
        }

        // Error 2174: timezone format warning — IBKR still delivers the data, do NOT remove from pending
        if (event.code() == 2174) {
            log.info("    Ignoring non-fatal warning 2174 (timezone format) for request {}: {}", event.id(), event.message());
            return;
        }

        log.warn("    Error received: id={}, code={}, message={}", event.id(), event.code(), event.message());
        if (pendingRequests.contains(event.id())) {
            log.warn("    Removing failed request {} from pending list (error {})", event.id(), event.code());
            failedRequests.put(event.id(), event.code());
            pendingRequests.remove(event.id());
            metrics.incrementIbkrError("error_" + event.code());
        }
    }

    private void onNextValidIdReceived(int firstOrderIdFromTws) {
        log.info("    Connection ready, nextOrderId set to {}", firstOrderIdFromTws);
        nextOrderId.set(Math.max(firstOrderIdFromTws, 1));
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

        // Mark as cancelled before calling cancelHistoricalData
        cancelledRequests.add(reqId);
        log.debug("    Cancelling historical data for reqId={}", reqId);
        client.cancelHistoricalData(reqId);
    }

    private void cleanupRequest(int reqId) {
        requestTickerMap.remove(reqId);
        requestTimeframeMap.remove(reqId);
        pendingRequests.remove(reqId);
        cancelledRequests.remove(reqId);  // Clean up cancelled tracking
    }

    // ===== Package-private test hooks =====
    // These methods allow unit tests to exercise the TWS error-propagation state machine
    // without a real TWS connection. They bypass the connection guard and reqHistoricalData call,
    // directly exercising the waitForRequestCompletion → failedRequests → throw path.
    //
    // Pattern: test calls simulatePendingRequest() to register a reqId (mimicking what
    // downloadHistoricalData would have done after sending the wire request), then fires
    // simulateTwsError() or simulateTwsRequestComplete() from another thread to unblock it.

    /**
     * Registers a pending request as if TWS had acknowledged the reqHistoricalData call.
     * Returns the reqId that was registered.
     *
     * <p>For use in tests only — not part of the production API.
     */
    int simulatePendingRequest(String ticker, TimeFrame tf) {
        String cacheKey = tf.toCacheKey(ticker);
        List<Candle> candles = Collections.synchronizedList(new ArrayList<>());
        receivedData.put(cacheKey, candles);
        int reqId = nextOrderId.getAndIncrement();
        registerRequest(reqId, ticker, tf);
        return reqId;
    }

    /**
     * Waits for the given pending request to complete (mimicking the wait inside
     * downloadHistoricalData) and returns the candle list if successful, or throws
     * {@link IbkrHistoricalDataException} if TWS signalled an error.
     *
     * <p>For use in tests only — not part of the production API.
     */
    List<Candle> simulateWaitForResult(int reqId, String ticker, TimeFrame tf) {
        String cacheKey = tf.toCacheKey(ticker);
        List<Candle> candles = receivedData.get(cacheKey);
        waitForRequestCompletion(reqId, ticker, tf);
        Integer errorCode = failedRequests.remove(reqId);
        cleanupRequest(reqId);
        if (errorCode != null) {
            throw new IbkrHistoricalDataException(errorCode,
                    "TWS error " + errorCode + " for " + ticker + " [" + tf + "]");
        }
        return new ArrayList<>(candles);
    }

    /**
     * Simulates a TWS error callback for the given reqId.
     *
     * <p>For use in tests only — not part of the production API.
     */
    void simulateTwsError(int reqId, int errorCode, String errorMsg) {
        onErrorReceived(new IbkrCallbackHandler.ErrorEvent(reqId, errorCode, errorMsg));
    }

    /**
     * Simulates a TWS {@code historicalDataEnd} callback for the given reqId.
     *
     * <p>For use in tests only — not part of the production API.
     */
    void simulateTwsRequestComplete(int reqId) {
        onRequestComplete(new IbkrCallbackHandler.RequestCompleteEvent(reqId, "", ""));
    }
}
