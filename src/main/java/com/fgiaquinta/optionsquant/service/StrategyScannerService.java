package com.fgiaquinta.optionsquant.service;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerConcurrency;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.*;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.strategy.utils.CandlestickPatternDetector;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import com.fgiaquinta.optionsquant.strategy.utils.SignalQualityFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Scans all configured tickers against all 12 strategies.
 * Returns triggered signals with trade plans.
 * Auto-downloads fresh data if CSV is missing or stale.
 */
@Slf4j
@Service
public class StrategyScannerService {

    // Progress tracking for UI
    private final AtomicInteger scannedCount = new AtomicInteger(0);
    private final AtomicInteger currentBatchSize = new AtomicInteger(0);
    private final AtomicReference<String> currentBatchLabel = new AtomicReference<>("");
    private final AtomicInteger totalToScan = new AtomicInteger(0);
    private volatile BooleanSupplier stopRequestedSupplier = () -> false;
    private volatile java.util.function.Consumer<String> scanActivityCallback = s -> {};
    private volatile java.util.function.BiConsumer<String, Integer> scanCompleteCallback = (ticker, signals) -> {};

    /** When set, scanAll uses this list instead of loading from config/CSV */
    private final AtomicReference<List<String>> tickerOverride = new AtomicReference<>(null);

    /**
     * Only one {@link #scanAll} at a time — avoids overlapping IBKR download pools (e.g. scheduler + REST + live)
     * so HOT batches are not starved by another scan's "remaining" tickers.
     */
    private final ReentrantLock scanAllExclusiveLock = new ReentrantLock(true);
    private final AtomicReference<String> scanOwnerThreadLabel = new AtomicReference<>("");

    private final CandleCsvService csvService;
    private final IbkrService ibkrService;
    private final IbkrProperties ibkrProperties;
    private final TickerService tickerService;
    private final TickerMemory tickerMemory;
    private final EarningsDateService earningsService;
    private final MarketCalendarService marketCalendar;
    private final ScannerProperties scannerProperties;
    private final ScanPrioritizationService scanPrioritizationService;

    private final List<TradingStrategy> callStrategies;
    private final List<TradingStrategy> putStrategies;

    // Live-replay-mode hooks — optional; null when feature disabled or not yet wired.
    @Autowired(required = false) private ReplayClock replayClock;
    @Autowired(required = false) private ReplayCandleSource replayCandleSource;

    // Separate logger for strategy analysis output
    private static final org.slf4j.Logger strategyLog = 
            org.slf4j.LoggerFactory.getLogger("StrategyAnalysis");
    
    // Separate logger for data download operations
    private static final org.slf4j.Logger downloadLog = 
            org.slf4j.LoggerFactory.getLogger("DataDownload");

    // ===== IBKR RATE LIMITING =====
    // IBKR TWS API limit: 50 messages/second (Error 100)
    // We use Guava's RateLimiter for precise rate limiting (non-blocking sleep)
    // and a bounded executor queue with CallerRunsPolicy for backpressure.
    private static final int MAX_CONCURRENT_DOWNLOADS = 10;
    private final RateLimiter downloadRateLimiter = RateLimiter.create(10.0); // 10 requests per second
    private final Semaphore downloadSemaphore = new Semaphore(MAX_CONCURRENT_DOWNLOADS);
    private volatile ExecutorService downloadExecutor = createDownloadExecutor();

    private static ExecutorService createDownloadExecutor() {
        return new ThreadPoolExecutor(
                MAX_CONCURRENT_DOWNLOADS,
                MAX_CONCURRENT_DOWNLOADS,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_CONCURRENT_DOWNLOADS * 5),
                r -> {
                    Thread t = new Thread(r, "IBKR-Downloader");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * Interrupts all in-flight downloads immediately by replacing the executor
     * pool and shutting down the old one. Call this on scan stop.
     */
    public void stopDownloads() {
        ExecutorService old = downloadExecutor;
        downloadExecutor = createDownloadExecutor();
        old.shutdownNow(); // sends interrupt to every download thread
        downloadSemaphore.release(MAX_CONCURRENT_DOWNLOADS); // unblock any waiting acquires
    }
    private final AtomicInteger activeDownloads = new AtomicInteger(0);

    // Maximum age for data to be considered "fresh"
    private static final Map<TimeFrame, Duration> FRESHNESS_THRESHOLDS = Map.of(
            TimeFrame.MIN_5, Duration.ofMinutes(15),
            TimeFrame.MIN_15, Duration.ofMinutes(30),
            TimeFrame.HOUR_1, Duration.ofHours(2),
            TimeFrame.DAY_1, Duration.ofHours(26)
    );

    public StrategyScannerService(CandleCsvService csvService, IbkrService ibkrService,
                                   IbkrProperties ibkrProperties, TickerService tickerService,
                                   TickerMemory tickerMemory, EarningsDateService earningsService,
                                   MarketCalendarService marketCalendar,
                                   ScannerProperties scannerProperties,
                                   ScanPrioritizationService scanPrioritizationService) {
        this.csvService = csvService;
        this.ibkrService = ibkrService;
        this.ibkrProperties = ibkrProperties;
        this.tickerService = tickerService;
        this.tickerMemory = tickerMemory;
        this.earningsService = earningsService;
        this.marketCalendar = marketCalendar;
        this.scannerProperties = scannerProperties;
        this.scanPrioritizationService = scanPrioritizationService;

        this.callStrategies = List.of(
                new C1SqueezeCallStrategy(),
                new C2TrendCallStrategy(),
                new C3BounceCallStrategy(),
                new C4OpeningCallStrategy(),
                new C5ContinuationCallStrategy(),
                new C6ReversalCallStrategy()
        );
        this.putStrategies = List.of(
                new P1SqueezePutStrategy(),
                new P2TrendPutStrategy(),
                new P3BouncePutStrategy(),
                new P4OpeningPutStrategy(),
                new P5ContinuationPutStrategy(),
                new P6ReversalPutStrategy()
        );
    }

    @PostConstruct
    void applyConfiguredConcurrency() {
        if (scannerProperties.concurrentMode() == ScannerProperties.ConcurrentMode.AUTO) {
            int ap = Runtime.getRuntime().availableProcessors();
            int v = ScannerConcurrency.computeAutoMaxConcurrent(
                    ap,
                    scannerProperties.autoReserveLogicalCpus(),
                    scannerProperties.autoMinConcurrent(),
                    scannerProperties.autoMaxConcurrentCap());
            setMaxConcurrentScans(v);
            log.info("Scanner concurrency AUTO → {} parallel tickers (availableProcessors={}, reserveLogical={}, cap=[{},{}])",
                    v, ap, scannerProperties.autoReserveLogicalCpus(),
                    scannerProperties.autoMinConcurrent(), scannerProperties.autoMaxConcurrentCap());
        } else {
            setMaxConcurrentScans(scannerProperties.fixedMaxConcurrent());
            log.info("Scanner concurrency FIXED → {} parallel tickers", scannerProperties.fixedMaxConcurrent());
        }
    }

    public void setStopRequestedSupplier(BooleanSupplier supplier) {
        this.stopRequestedSupplier = supplier;
    }

    /**
     * Resets all progress counters to zero. Called on startup to clear stale state
     * from a previous session that may have been interrupted mid-scan.
     */
    public void resetProgress() {
        scannedCount.set(0);
        currentBatchSize.set(0);
        currentBatchLabel.set("");
        totalToScan.set(0);
    }

    /**
     * Clears the stop request flag. Use this to recover from a stuck scan state.
     */
    public void clearStopRequest() {
        // This method is called by LiveModeController to reset the stop flag
        // The actual flag is managed by the controller's AtomicBoolean
    }

    public void setScanActivityCallback(java.util.function.Consumer<String> callback) {
        this.scanActivityCallback = callback;
    }

    public void setScanCompleteCallback(java.util.function.BiConsumer<String, Integer> callback) {
        this.scanCompleteCallback = callback;
    }

    /**
     * Shuts down the download executor on application context destruction.
     * Ensures clean termination of background download threads.
     */
    @PreDestroy
    public void shutdown() {
        downloadExecutor.shutdown();
        try {
            if (!downloadExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                downloadLog.warn("Download executor did not terminate gracefully, forcing shutdown...");
                downloadExecutor.shutdownNow();
                if (!downloadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    downloadLog.error("Download executor did not terminate even after shutdownNow()");
                }
            }
        } catch (InterruptedException e) {
            downloadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // Maximum number of tickers to scan concurrently (runtime-configurable)
    // Default is 4, can be changed at runtime via setMaxConcurrentScans()
    private final AtomicInteger maxConcurrentTickerScans = new AtomicInteger(4);
    private final ExecutorService tickerScanExecutor = new ThreadPoolExecutor(
            1,  // core pool size (min)
            16, // max pool size (will be capped by setMaxConcurrentScans)
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "Ticker-Scanner");
                t.setDaemon(true);
                return t;
            }
    );

    /**
     * Get the current max concurrent ticker scans value.
     */
    public int getMaxConcurrentScans() {
        return maxConcurrentTickerScans.get();
    }

    /**
     * Set the max concurrent ticker scans at runtime.
     * Takes effect immediately on the next scan batch (or immediately if a scan is in progress
     * by adjusting the number of active threads in the pool).
     */
    public void setMaxConcurrentScans(int count) {
        int newValue = Math.max(1, Math.min(16, count));
        maxConcurrentTickerScans.set(newValue);
        // Update the executor's maximum pool size dynamically
        ThreadPoolExecutor tpe = (ThreadPoolExecutor) tickerScanExecutor;
        tpe.setMaximumPoolSize(newValue);
        log.info("Max concurrent ticker scans set to {}", newValue);
    }

    /** Override which tickers the next scanAll will use (null = use config default). */
    public void setTickerOverride(List<String> tickers) { tickerOverride.set(tickers); }
    public void clearTickerOverride() { tickerOverride.set(null); }

    /**
     * Scans all tickers against all strategies with hot tickers first.
     */
    public ScanResult scanAll(boolean includeTradePlans, boolean autoRefreshData) {
        return scanAll(includeTradePlans, autoRefreshData, false);
    }

    /**
     * @param lockWaitMs {@code -1} block until the lock is acquired; {@code 0} try once; {@code >0} try up to this many ms
     */
    public ScanResult scanAll(boolean includeTradePlans, boolean autoRefreshData, boolean deterministicMode, long lockWaitMs) {
        boolean locked = acquireScanAllLock(lockWaitMs);
        if (!locked) {
            log.warn("scanAll skipped — exclusive scan lock not acquired within {}ms (another scan owns IBKR downloads)",
                    lockWaitMs >= 0 ? lockWaitMs : 0);
            return new ScanResult(0, 0, Collections.emptyList(), 0, true);
        }
        scanOwnerThreadLabel.set(Thread.currentThread().getName());
        try {
            return scanAllBody(includeTradePlans, autoRefreshData, deterministicMode);
        } finally {
            scanOwnerThreadLabel.set("");
            scanAllExclusiveLock.unlock();
        }
    }

    private boolean acquireScanAllLock(long lockWaitMs) {
        try {
            if (lockWaitMs < 0) {
                scanAllExclusiveLock.lockInterruptibly();
                return true;
            }
            if (lockWaitMs == 0) {
                return scanAllExclusiveLock.tryLock();
            }
            return scanAllExclusiveLock.tryLock(lockWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** True if any thread is inside {@link #scanAll} (holding the exclusive lock). */
    public boolean isScanAllLockHeld() {
        return scanAllExclusiveLock.isLocked();
    }

    public String getScanOwnerThreadLabel() {
        return scanOwnerThreadLabel.get();
    }

    public ScanResult scanAll(boolean includeTradePlans, boolean autoRefreshData, boolean deterministicMode) {
        return scanAll(includeTradePlans, autoRefreshData, deterministicMode, -1L);
    }

    private ScanResult scanAllBody(boolean includeTradePlans, boolean autoRefreshData, boolean deterministicMode) {
        // Get all tickers from CSV or YAML (or from override set by the caller)
        List<String> override = tickerOverride.get();
        List<String> allTickers = (override != null && !override.isEmpty())
                ? override
                : (ibkrProperties.useCsvTickers() ? tickerService.getTickerSymbols() : ibkrProperties.tickers());

        // HOT first, order = ticker config (runtime → CSV market-cap fallback)
        // TODO: useCsvTickers flag is always true in prod — see change remove-use-csv-tickers-flag
        List<String> hotOrder = tickerService.getHotTickers();
        List<String> hotTickersToScan = tickerService.orderHotForScan(allTickers, hotOrder);
        java.util.Set<String> hotSet = new java.util.LinkedHashSet<>(hotTickersToScan);

        List<String> remainingTickers = allTickers.stream()
                .filter(t -> !hotSet.contains(t))
                .toList();

        List<String> orderedRemaining = scanPrioritizationService.orderRemainingTickers(remainingTickers);
        if (scannerProperties.prioritizationMode() == ScannerProperties.PrioritizationMode.HYBRID
                && !orderedRemaining.isEmpty()) {
            log.info(">>> Hybrid prioritization: first remaining tickers (sample): {}",
                    orderedRemaining.stream().limit(Math.min(8, orderedRemaining.size())).toList());
        }

        log.info(">>> Scanning {} tickers ({} hot first, {} remaining) against 12 strategies (autoRefresh={})",
                allTickers.size(), hotTickersToScan.size(), orderedRemaining.size(), autoRefreshData);

        long startTime = System.currentTimeMillis();
        List<Signal> allSignals = Collections.synchronizedList(new ArrayList<>());

        // Reset progress counters
        scannedCount.set(0);
        currentBatchLabel.set("");
        currentBatchSize.set(0);
        totalToScan.set(allTickers.size());

        // SCAN HOT TICKERS FIRST (parallel with stop support)
        if (!hotTickersToScan.isEmpty()) {
            currentBatchLabel.set("Hot tickers: 0/" + hotTickersToScan.size());
            currentBatchSize.set(hotTickersToScan.size());
            if (deterministicMode) {
                log.info("🔥 [Deterministic] Scanning {} HOT tickers from cached data: {}", hotTickersToScan.size(), hotTickersToScan);
            } else {
                log.info("🔥 Scanning {} HOT tickers first (parallel, {} threads): {}", hotTickersToScan.size(), maxConcurrentTickerScans.get(), hotTickersToScan);
            }
            List<Future<List<Signal>>> hotFutures = new ArrayList<>();
            for (String ticker : hotTickersToScan) {
                if (stopRequestedSupplier.getAsBoolean()) {
                    log.info("⏹ Scan stopped by user during hot tickers scan");
                    currentBatchLabel.set("Stopped");
                    break;
                }
                scanActivityCallback.accept(ticker + ":LOADING"); // Emitting granular status
                final String t = ticker;
                hotFutures.add(tickerScanExecutor.submit(() -> {
                    try {
                        scanActivityCallback.accept(t + ":SCANNING");
                        List<Signal> signals = scanTicker(t, includeTradePlans, autoRefreshData && !deterministicMode);
                        int done = scannedCount.incrementAndGet();
                        currentBatchLabel.set("Hot tickers: " + done + "/" + currentBatchSize.get());
                        scanCompleteCallback.accept(t, signals.size());
                        return signals;
                    } catch (Exception e) {
                        log.error("Error scanning hot ticker {}: {}", t, e.getMessage());
                        scanCompleteCallback.accept(t, -1);
                        return Collections.<Signal>emptyList();
                    }
                }));
            }
            // Collect results with stop support
            for (Future<List<Signal>> future : hotFutures) {
                if (stopRequestedSupplier.getAsBoolean()) {
                    future.cancel(true);
                    continue;
                }
                try {
                    // Use a short timeout to keep checking stopRequestedSupplier
                    allSignals.addAll(future.get(1, TimeUnit.SECONDS));
                } catch (TimeoutException te) {
                    // Try again in next iteration of inner while/loop or just re-get
                    boolean collected = false;
                    while (!collected && !stopRequestedSupplier.getAsBoolean()) {
                        try {
                            allSignals.addAll(future.get(1, TimeUnit.SECONDS));
                            collected = true;
                        } catch (TimeoutException innerTe) {
                            // Still waiting...
                        } catch (InterruptedException | ExecutionException e) {
                            log.error("Error during hot ticker collection: {}", e.getMessage());
                            break;
                        }
                    }
                    if (!collected) future.cancel(true);
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Interrupted or execution error for hot ticker: {}", e.getMessage());
                    future.cancel(true);
                } catch (Exception e) {
                    log.error("Unexpected error collecting hot ticker result: {}", e.getMessage());
                }
            }
            currentBatchLabel.set("");
            if (deterministicMode) {
                log.info("✅ Hot tickers scan complete (deterministic) - {} signals found", allSignals.size());
            } else {
                log.info("✅ Hot tickers scan complete - {} signals found", allSignals.size());
            }
        }

        // SCAN REMAINING TICKERS (parallel with stop support)
        if (!orderedRemaining.isEmpty()) {
            int hotDone = scannedCount.get();
            currentBatchLabel.set("Remaining: 0/" + orderedRemaining.size() + " (" + hotDone + "/" + allTickers.size() + " total)");
            currentBatchSize.set(orderedRemaining.size());
            if (deterministicMode) {
                log.info("📊 [Deterministic] Scanning {} remaining tickers from cached data (parallel)...", orderedRemaining.size());
            } else {
                log.info("📊 Scanning {} remaining tickers (parallel, {} threads)...", orderedRemaining.size(), maxConcurrentTickerScans.get());
            }
            List<Future<List<Signal>>> remainingFutures = new ArrayList<>();
            for (String ticker : orderedRemaining) {
                if (stopRequestedSupplier.getAsBoolean()) {
                    log.info("⏹ Scan stopped by user during remaining tickers scan");
                    currentBatchLabel.set("Stopped");
                    break;
                }
                scanActivityCallback.accept(ticker + ":LOADING");
                final String t = ticker;
                remainingFutures.add(tickerScanExecutor.submit(() -> {
                    try {
                        scanActivityCallback.accept(t + ":SCANNING");
                        List<Signal> signals = scanTicker(t, includeTradePlans, autoRefreshData && !deterministicMode);
                        int done = scannedCount.incrementAndGet();
                        currentBatchLabel.set("Total: " + done + "/" + totalToScan.get());
                        scanCompleteCallback.accept(t, signals.size());
                        return signals;
                    } catch (Exception e) {
                        log.error("Error scanning ticker {}: {}", t, e.getMessage());
                        scanCompleteCallback.accept(t, -1);
                        return Collections.<Signal>emptyList();
                    }
                }));
            }
            // Collect results with stop support
            for (Future<List<Signal>> future : remainingFutures) {
                if (stopRequestedSupplier.getAsBoolean()) {
                    future.cancel(true);
                    continue;
                }
                try {
                    // Use a short timeout to keep checking stopRequestedSupplier
                    allSignals.addAll(future.get(1, TimeUnit.SECONDS));
                } catch (TimeoutException te) {
                    boolean collected = false;
                    while (!collected && !stopRequestedSupplier.getAsBoolean()) {
                        try {
                            allSignals.addAll(future.get(1, TimeUnit.SECONDS));
                            collected = true;
                        } catch (TimeoutException innerTe) {
                            // Still waiting...
                        } catch (InterruptedException | ExecutionException e) {
                            log.error("Error during ticker collection: {}", e.getMessage());
                            break;
                        }
                    }
                    if (!collected) future.cancel(true);
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Interrupted or execution error for ticker: {}", e.getMessage());
                    future.cancel(true);
                } catch (Exception e) {
                    log.error("Unexpected error collecting ticker result: {}", e.getMessage());
                }
            }
        }

        // Clear progress when done
        currentBatchLabel.set("Scan complete");

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< Scan complete: {} signals found across {} tickers in {}ms",
                allSignals.size(), allTickers.size(), elapsed);

        return new ScanResult(allSignals.size(), allTickers.size(), allSignals, elapsed, false);
    }

    /**
     * Scans all tickers with auto-refresh enabled by default.
     */
    public ScanResult scanAll(boolean includeTradePlans) {
        return scanAll(includeTradePlans, true);
    }

    /**
     * Scans a single ticker against all strategies.
     */
    public List<Signal> scanTicker(String ticker, boolean includeTradePlans, boolean autoRefreshData) {
        long tickerStartTime = System.currentTimeMillis();
        log.debug("Scanning ticker: {} (autoRefresh={})", ticker, autoRefreshData);

        // ===== TICKER MEMORY CHECK =====
        // Block tickers with consistently poor performance
        if (tickerMemory.isBlocked(ticker)) {
            strategyLog.debug("🚫 [Memory] Skipping {} — blocked due to poor historical performance", ticker);
            return Collections.emptyList();
        }

        // ===== EARNINGS CHECK =====
        // Skip tickers with earnings in the next 3 days (IV crush risk)
        if (earningsService.hasEarningsSoon(ticker, 3)) {
            LocalDate earningsDate = earningsService.getEarningsDate(ticker);
            strategyLog.debug("📅 [Earnings] Skipping {} — earnings on {}", ticker, earningsDate);
            return Collections.emptyList();
        }

        // ===== PARALLEL DATA LOADING =====
        // Load all timeframes concurrently with rate limiting
        Map<TimeFrame, List<Candle>> candlesByTimeframe = new ConcurrentHashMap<>();
        AtomicInteger totalNewCandles = new AtomicInteger(0);

        List<TimeFrame> timeframesToLoad = Arrays.asList(TimeFrame.values());
        List<CompletableFuture<Void>> downloadFutures = new ArrayList<>();

        // ===== REPLAY MODE SHORT-CIRCUIT =====
        // During live-replay-mode: bypass CSV/IBKR entirely; use pre-loaded candles
        // truncated to virtualNow so the scanner sees a simulated live feed.
        boolean replayActive = replayClock != null && replayClock.isActive() && replayCandleSource != null;
        if (replayActive) {
            ZonedDateTime virtualNow = replayClock.getNow();
            for (TimeFrame tf : timeframesToLoad) {
                List<Candle> visible = replayCandleSource.getCandlesUntil(ticker, tf, virtualNow);
                if (!visible.isEmpty()) candlesByTimeframe.put(tf, visible);
            }
        } else for (TimeFrame tf : timeframesToLoad) {
            List<Candle> cachedCandles = csvService.loadFromCsv(ticker, tf);

            if (cachedCandles.isEmpty()) {
                // Need full download - submit to parallel executor
                downloadFutures.add(CompletableFuture.runAsync(() -> {
                    // Check for stop request before starting download
                    if (stopRequestedSupplier.getAsBoolean()) {
                        downloadLog.debug("⏹ Stop requested before downloading {} [{}]", ticker, tf);
                        return;
                    }
                    try {
                        // Poll the semaphore so we can abort while waiting for a free slot
                        while (!downloadSemaphore.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                            if (stopRequestedSupplier.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                                downloadLog.debug("⏹ Abort while waiting for semaphore {} [{}]", ticker, tf);
                                return;
                            }
                        }
                        if (stopRequestedSupplier.getAsBoolean()) { downloadSemaphore.release(); return; }
                        activeDownloads.incrementAndGet();

                        List<Candle> freshData = downloadTimeframeDelta(ticker, tf, null);
                        if (!freshData.isEmpty()) {
                            csvService.saveToCsv(ticker, tf, freshData);
                            candlesByTimeframe.put(tf, freshData);
                            totalNewCandles.addAndGet(freshData.size());
                        }

                        downloadRateLimiter.acquire();  // Rate limit
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        downloadLog.error("❌ Download interrupted for {} [{}]", ticker, tf);
                    } catch (Exception e) {
                        downloadLog.error("❌ Failed to download {} [{}]: {}", ticker, tf, e.getMessage());
                    } finally {
                        activeDownloads.decrementAndGet();
                        downloadSemaphore.release();
                    }
                }, downloadExecutor));

            } else if (autoRefreshData) {
                // Check if we should download using market-aware logic
                ZonedDateTime lastTimestamp = getLastTimestamp(cachedCandles);
                int tfMinutes = timeframeToMinutes(tf);
                Duration threshold = FRESHNESS_THRESHOLDS.getOrDefault(tf, Duration.ofHours(2));
                int thresholdMinutes = (int) threshold.toMinutes();

                if (marketCalendar.shouldDownloadData(lastTimestamp, tfMinutes, thresholdMinutes)) {
                    // Need delta download - submit to parallel executor
                    final List<Candle> cached = cachedCandles;  // For lambda
                    downloadFutures.add(CompletableFuture.runAsync(() -> {
                        // Check for stop request before starting download
                        if (stopRequestedSupplier.getAsBoolean()) {
                            downloadLog.debug("⏹ Stop requested before delta downloading {} [{}]", ticker, tf);
                            return;
                        }
                        try {
                            while (!downloadSemaphore.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                                if (stopRequestedSupplier.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                                    downloadLog.debug("⏹ Abort waiting for semaphore (delta) {} [{}]", ticker, tf);
                                    return;
                                }
                            }
                            if (stopRequestedSupplier.getAsBoolean()) { downloadSemaphore.release(); return; }
                            activeDownloads.incrementAndGet();

                            List<Candle> deltaData = downloadTimeframeDelta(ticker, tf, lastTimestamp);

                            if (!deltaData.isEmpty()) {
                                List<Candle> mergedCandles = mergeCandles(cached, deltaData);
                                csvService.saveToCsv(ticker, tf, mergedCandles);
                                candlesByTimeframe.put(tf, mergedCandles);
                                totalNewCandles.addAndGet(deltaData.size());
                                downloadLog.info("✅ {} [{}] delta: +{} new candles (cached: {} → merged: {})",
                                        ticker, tf, deltaData.size(), cached.size(), mergedCandles.size());
                            } else {
                                candlesByTimeframe.put(tf, cached);
                            }

                            downloadRateLimiter.acquire();  // Rate limit
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            downloadLog.error("❌ Download interrupted for {} [{}]", ticker, tf);
                        } catch (Exception e) {
                            downloadLog.error("❌ Failed to download {} [{}]: {}", ticker, tf, e.getMessage());
                        } finally {
                            activeDownloads.decrementAndGet();
                            downloadSemaphore.release();
                        }
                    }, downloadExecutor));
                } else {
                    // Market calendar says no new data possible, use cached
                    downloadLog.debug("📅 {} [{}] skipping download - market calendar says no new data (last candle: {})",
                            ticker, tf, lastTimestamp);
                    candlesByTimeframe.put(tf, cachedCandles);
                }
            } else {
                // Use cached data (no download needed)
                candlesByTimeframe.put(tf, cachedCandles);
            }
        }

        // Wait for all downloads to complete (with stop checking)
        if (!downloadFutures.isEmpty()) {
            downloadLog.info("📥 {} downloading {} timeframes in parallel...", ticker, downloadFutures.size());
            
            // Check for stop request before waiting
            if (stopRequestedSupplier.getAsBoolean()) {
                downloadLog.info("⏹ Stop requested during {} downloads - cancelling...", ticker);
                // Cancel all pending/in-progress downloads
                for (CompletableFuture<Void> future : downloadFutures) {
                    future.cancel(true);
                }
                return Collections.emptyList();
            }
            
            CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture<?>[0])).join();
            downloadLog.info("✅ {} downloads complete (+{} new candles)", ticker, totalNewCandles.get());
        }

        if (totalNewCandles.get() > 0) {
            downloadLog.info("📊 {} refresh complete: +{} new candles across timeframes", ticker, totalNewCandles.get());
        }

        if (candlesByTimeframe.isEmpty()) {
            log.debug("No usable data for ticker {}", ticker);
            return Collections.emptyList();
        }

        StrategyData data = new StrategyData(candlesByTimeframe);
        if (!data.hasAllTimeframes()) {
            log.debug("Incomplete data for ticker {} (have {}, need 4 timeframes)", ticker, candlesByTimeframe.keySet());
            return Collections.emptyList();
        }

        // Use the latest candle's timestamp as "current time"
        ZonedDateTime currentTime = getLatestTimestamp(data);
        if (currentTime == null) return Collections.emptyList();

        // Convert to NY timezone for strategy time checks
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        List<Signal> signals = new ArrayList<>();
        List<TradingStrategy> allStrategies = new ArrayList<>();
        allStrategies.addAll(callStrategies);
        allStrategies.addAll(putStrategies);

        for (TradingStrategy strategy : allStrategies) {
            try {
                boolean triggered = strategy.isTriggered(ticker, data, nyTime);
                if (!triggered) continue;
                
                // ===== POST-TRIGGER: Signal Quality Filter =====
                // Run generic false signal detection on the 1-hour series
                BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
                boolean isCall = strategy.getName().contains("call");

                if (series1h == null || series1h.isEmpty()) continue;
                
                int idx1h = data.getIndexForTime(series1h, currentTime);
                if (idx1h > 0 && !SignalQualityFilter.passesCoreChecks(series1h, idx1h, isCall)) {
                    String qualityReport = SignalQualityFilter.getQualityReport(series1h, idx1h, isCall);
                    strategyLog.debug("🚫 [Quality Filter] {} {} failed quality check: {}",
                            ticker, strategy.getName(), qualityReport);
                    continue;  // Skip this signal — likely false
                }

                // ===== CANDLESTICK PATTERN DETECTION =====
                String candlestickPattern = CandlestickPatternDetector.detectPattern(series1h, idx1h);
                String strategyPattern = extractPatternFromStrategy(strategy.getName());
                String combinedPattern = strategyPattern + " + " + candlestickPattern;

                // ===== LEARNED PATTERN FILTERING =====
                // Check if this pattern has been disabled for this ticker+strategy
                if (!tickerMemory.isPatternAllowed(ticker, strategy.getName(), combinedPattern)) {
                    strategyLog.debug("🚫 [Pattern Filter] {} {} disabled pattern '{}' — skipping",
                            ticker, strategy.getName(), combinedPattern);
                    continue;  // Skip signals with historically poor patterns
                }

                double currentPrice = getCurrentPrice(data);

                TradePlan tradePlan = null;
                if (includeTradePlans) {
                    TickerStrategyProfile profile = tickerMemory.getStrategyProfile(ticker, strategy.getName());
                    tradePlan = RiskCalculator.generatePlan(data, ticker, currentTime, isCall, currentPrice, strategy.getName(), profile);
                }

                Signal signal = new Signal(
                        ticker,
                        strategy.getName(),
                        isCall ? "CALL" : "PUT",
                        currentPrice,
                        currentTime,
                        tradePlan,
                        combinedPattern,  // Include pattern in signal
                        replayActive      // UI/audit tag — routes to replaySignals list (execution unchanged)
                );

                signals.add(signal);
                strategyLog.info("🎯 SIGNAL: {} triggered {} at ${} [pattern: {}]", 
                        ticker, strategy.getName(), currentPrice, combinedPattern);
                        
            } catch (Exception e) {
                log.warn("Error evaluating strategy {} for ticker {}: {}", strategy.getName(), ticker, e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - tickerStartTime;
        logTickerResult(ticker, signals, elapsed);
        return signals;
    }

    /**
     * Downloads data for a single timeframe. If lastTimestamp is null, does a full download.
     * Otherwise, downloads only delta (new candles since lastTimestamp).
     */
    private List<Candle> downloadTimeframeDelta(String ticker, TimeFrame tf, ZonedDateTime lastTimestamp) {
        try {
            if (lastTimestamp == null) {
                // Full download
                return ibkrService.downloadHistoricalData(ticker, tf);
            } else {
                // Delta download
                return ibkrService.downloadDelta(ticker, tf, lastTimestamp);
            }
        } catch (Exception e) {
            downloadLog.error("❌ Failed to download {} [{}]: {}", ticker, tf, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Merges cached candles with freshly downloaded delta candles.
     * Deduplicates by timestamp and returns a sorted list.
     */
    private List<Candle> mergeCandles(List<Candle> cachedCandles, List<Candle> deltaCandles) {
        if (cachedCandles == null || cachedCandles.isEmpty()) {
            return deltaCandles != null ? new ArrayList<>(deltaCandles) : Collections.emptyList();
        }
        if (deltaCandles == null || deltaCandles.isEmpty()) {
            return new ArrayList<>(cachedCandles);
        }

        // Use a LinkedHashMap to deduplicate by timestamp (delta candles overwrite cached ones)
        Map<ZonedDateTime, Candle> merged = new LinkedHashMap<>();
        for (Candle candle : cachedCandles) {
            merged.put(candle.timestamp(), candle);
        }
        for (Candle candle : deltaCandles) {
            merged.put(candle.timestamp(), candle);
        }

        // Sort by timestamp and return
        List<Candle> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(Candle::timestamp));
        return result;
    }

    /**
     * Logs per-ticker analysis completion.
     */
    private void logTickerResult(String ticker, List<Signal> signals, long elapsedMs) {
        if (signals.isEmpty()) {
            strategyLog.debug("✅ {} analyzed - no signals ({})", ticker, elapsedMs + "ms");
        } else {
            strategyLog.info("📊 {} analyzed - {} signal(s) found ({})", 
                    ticker, signals.size(), elapsedMs + "ms");
            for (Signal signal : signals) {
                strategyLog.info("   {} {} @ ${} - {} (TP: ${}, SL: ${})", 
                        signal.ticker(), signal.direction(), signal.currentPrice(),
                        signal.strategy(),
                        signal.tradePlan() != null ? signal.tradePlan().takeProfit : "N/A",
                        signal.tradePlan() != null ? signal.tradePlan().stopLoss : "N/A");
            }
        }
    }

    /**
     * Scans a single ticker with auto-refresh enabled by default.
     */
    public List<Signal> scanTicker(String ticker, boolean includeTradePlans) {
        return scanTicker(ticker, includeTradePlans, true);
    }

    // ===== Helpers =====

    private static int timeframeToMinutes(TimeFrame tf) {
        return switch (tf) {
            case MIN_5 -> 5;
            case MIN_15 -> 15;
            case HOUR_1 -> 60;
            case DAY_1 -> 1440;
        };
    }

    private ZonedDateTime getLastTimestamp(List<Candle> candles) {
        return candles.get(candles.size() - 1).timestamp();
    }

    /**
     * Extracts a human-readable pattern name from the strategy name.
     */
    private String extractPatternFromStrategy(String strategyName) {
        String base = strategyName.toLowerCase()
                .replace("call", "")
                .replace("put", "")
                .replaceAll("c\\d+|p\\d+", "");
        
        return switch (base) {
            case "squeeze" -> "squeeze_breakout";
            case "trend" -> "trend_continuation";
            case "bounce" -> "support_resistance_bounce";
            case "opening" -> "opening_range";
            case "continuation" -> "gap_continuation";
            case "reversal" -> "reversal";
            default -> base.isEmpty() ? "unknown" : base;
        };
    }

    private ZonedDateTime getLatestTimestamp(StrategyData data) {
        ZonedDateTime latest = null;
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = data.getCandles(tf);
            if (candles != null && !candles.isEmpty()) {
                ZonedDateTime ts = candles.get(candles.size() - 1).timestamp();
                if (latest == null || ts.isAfter(latest)) {
                    latest = ts;
                }
            }
        }
        return latest;
    }

    private double getCurrentPrice(StrategyData data) {
        List<Candle> candles5m = data.getCandles(TimeFrame.MIN_5);
        if (candles5m != null && !candles5m.isEmpty()) {
            return candles5m.get(candles5m.size() - 1).close();
        }
        List<Candle> candles15m = data.getCandles(TimeFrame.MIN_15);
        if (candles15m != null && !candles15m.isEmpty()) {
            return candles15m.get(candles15m.size() - 1).close();
        }
        return 0;
    }

    // ---- Response Records ----

    /** Returns how many tickers have been scanned so far in the current scan */
    public int getScannedCount() { return scannedCount.get(); }
    /** Returns current batch label (e.g., "Hot: 5/14 done") */
    public String getCurrentBatchLabel() { return currentBatchLabel.get(); }
    /** Returns total tickers to scan in current operation */
    public int getTotalToScan() { return totalToScan.get(); }

    /**
     * Gets the last known price for a ticker from its latest 5m or 15m candle.
     */
    public double getLastKnownPrice(String ticker) {
        List<Candle> candles = csvService.loadFromCsv(ticker, TimeFrame.MIN_5);
        if (candles.isEmpty()) {
            candles = csvService.loadFromCsv(ticker, TimeFrame.MIN_15);
        }
        if (!candles.isEmpty()) {
            return candles.get(candles.size() - 1).close();
        }
        return 0;
    }

    /**
     * @param lockSkipped {@code true} if {@link #scanAll} could not take the exclusive lock (e.g. scheduler timeout)
     */
    public record ScanResult(
            int totalSignals,
            int tickersScanned,
            List<Signal> signals,
            long elapsedMs,
            boolean lockSkipped
    ) {
        public ScanResult(int totalSignals, int tickersScanned, List<Signal> signals, long elapsedMs) {
            this(totalSignals, tickersScanned, signals, elapsedMs, false);
        }
    }

    public record Signal(
            String ticker,
            String strategy,
            String direction,
            double currentPrice,
            ZonedDateTime timestamp,
            TradePlan tradePlan,
            String candlestickPattern,  // detected pattern (e.g., "squeeze_breakout + hammer")
            boolean replay              // true when emitted during live-replay-mode (UI/audit only)
    ) {
        // Backward-compatible: no pattern, no replay
        public Signal(String ticker, String strategy, String direction, double currentPrice,
                     ZonedDateTime timestamp, TradePlan tradePlan) {
            this(ticker, strategy, direction, currentPrice, timestamp, tradePlan, "unknown", false);
        }

        // Backward-compatible: with pattern, no replay
        public Signal(String ticker, String strategy, String direction, double currentPrice,
                     ZonedDateTime timestamp, TradePlan tradePlan, String candlestickPattern) {
            this(ticker, strategy, direction, currentPrice, timestamp, tradePlan, candlestickPattern, false);
        }
    }
}
