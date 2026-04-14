package com.fgiaquinta.optionsquant.service;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.*;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.strategy.utils.CandlestickPatternDetector;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import com.fgiaquinta.optionsquant.strategy.utils.SignalQualityFilter;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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

    private final CandleCsvService csvService;
    private final IbkrService ibkrService;
    private final IbkrProperties ibkrProperties;
    private final TickerService tickerService;
    private final TickerMemory tickerMemory;
    private final EarningsDateService earningsService;

    private final List<TradingStrategy> callStrategies;
    private final List<TradingStrategy> putStrategies;

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
    private final ExecutorService downloadExecutor = new ThreadPoolExecutor(
            MAX_CONCURRENT_DOWNLOADS,
            MAX_CONCURRENT_DOWNLOADS,
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_CONCURRENT_DOWNLOADS * 5), // Bounded queue
            r -> {
                Thread t = new Thread(r, "IBKR-Downloader");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure: run in calling thread if queue full
    );
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
                                   TickerMemory tickerMemory, EarningsDateService earningsService) {
        this.csvService = csvService;
        this.ibkrService = ibkrService;
        this.ibkrProperties = ibkrProperties;
        this.tickerService = tickerService;
        this.tickerMemory = tickerMemory;
        this.earningsService = earningsService;

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

    /**
     * Scans all tickers against all strategies with hot tickers first.
     * Hot tickers (SPY, QQQ, AAPL, NVDA, TSLA, etc.) are scanned immediately,
     * then the remaining tickers are scanned.
     *
     * @param deterministicMode if true, skips delta downloads and uses only cached CSV data
     */
    public ScanResult scanAll(boolean includeTradePlans, boolean autoRefreshData) {
        return scanAll(includeTradePlans, autoRefreshData, false);
    }

    /**
     * Scans all tickers against all strategies with hot tickers first.
     *
     * @param deterministicMode if true, skips delta downloads and uses only cached CSV data for reproducible results
     */
    public ScanResult scanAll(boolean includeTradePlans, boolean autoRefreshData, boolean deterministicMode) {
        // Get all tickers from CSV or YAML
        List<String> allTickers = ibkrProperties.useCsvTickers() 
                ? tickerService.getTickerSymbols()
                : ibkrProperties.tickers();
        
        // Get hot tickers (priority list)
        List<String> hotTickers = ibkrProperties.hotTickers() != null 
                ? ibkrProperties.hotTickers() 
                : List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD");
        
        // Separate hot tickers from the rest
        List<String> hotTickersToScan = allTickers.stream()
                .filter(hotTickers::contains)
                .toList();
        
        List<String> remainingTickers = allTickers.stream()
                .filter(t -> !hotTickers.contains(t))
                .toList();
        
        log.info(">>> Scanning {} tickers ({} hot first, {} remaining) against 12 strategies (autoRefresh={})", 
                allTickers.size(), hotTickersToScan.size(), remainingTickers.size(), autoRefreshData);
        
        long startTime = System.currentTimeMillis();
        List<Signal> allSignals = new ArrayList<>();

        // Reset progress counters
        scannedCount.set(0);
        currentBatchLabel.set("");
        currentBatchSize.set(0);
        totalToScan.set(allTickers.size());

        // SCAN HOT TICKERS FIRST (parallelized for performance)
        if (!hotTickersToScan.isEmpty()) {
            currentBatchLabel.set("Hot tickers: 0/" + hotTickersToScan.size());
            currentBatchSize.set(hotTickersToScan.size());
            if (deterministicMode) {
                log.info("🔥 [Deterministic] Scanning {} HOT tickers from cached data: {}", hotTickersToScan.size(), hotTickersToScan);
            } else {
                log.info("🔥 Scanning {} HOT tickers first (parallel): {}", hotTickersToScan.size(), hotTickersToScan);
            }
            List<List<Signal>> hotResults = hotTickersToScan.parallelStream()
                .map(ticker -> {
                    try {
                        return scanTicker(ticker, includeTradePlans, autoRefreshData && !deterministicMode);
                    } catch (Exception e) {
                        log.error("Error scanning hot ticker {}: {}", ticker, e.getMessage());
                        return Collections.<Signal>emptyList();
                    } finally {
                        int done = scannedCount.incrementAndGet();
                        currentBatchLabel.set("Hot tickers: " + done + "/" + currentBatchSize.get());
                    }
                })
                .collect(Collectors.toList());
            hotResults.forEach(allSignals::addAll);
            currentBatchLabel.set("");
            if (deterministicMode) {
                log.info("✅ Hot tickers scan complete (deterministic) - {} signals found", allSignals.size());
            } else {
                log.info("✅ Hot tickers scan complete - {} signals found", allSignals.size());
            }
        }

        // SCAN REMAINING TICKERS (parallelized for performance)
        if (!remainingTickers.isEmpty()) {
            int hotDone = scannedCount.get();
            currentBatchLabel.set("Remaining: " + hotDone + "/" + allTickers.size() + " total");
            currentBatchSize.set(remainingTickers.size());
            if (deterministicMode) {
                log.info("📊 [Deterministic] Scanning {} remaining tickers from cached data (parallel)...", remainingTickers.size());
            } else {
                log.info("📊 Scanning {} remaining tickers (parallel)...", remainingTickers.size());
            }
            List<List<Signal>> remainingResults = remainingTickers.parallelStream()
                .map(ticker -> {
                    try {
                        return scanTicker(ticker, includeTradePlans, autoRefreshData && !deterministicMode);
                    } catch (Exception e) {
                        log.error("Error scanning ticker {}: {}", ticker, e.getMessage());
                        return Collections.<Signal>emptyList();
                    } finally {
                        int done = scannedCount.incrementAndGet();
                        currentBatchLabel.set("Total: " + done + "/" + totalToScan.get());
                    }
                })
                .collect(Collectors.toList());
            remainingResults.forEach(allSignals::addAll);
        }

        // Clear progress when done
        currentBatchLabel.set("Scan complete");

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< Scan complete: {} signals found across {} tickers in {}ms",
                allSignals.size(), allTickers.size(), elapsed);

        return new ScanResult(allSignals.size(), allTickers.size(), allSignals, elapsed);
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

        for (TimeFrame tf : timeframesToLoad) {
            List<Candle> cachedCandles = csvService.loadFromCsv(ticker, tf);
            
            if (cachedCandles.isEmpty()) {
                // Need full download - submit to parallel executor
                downloadFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        downloadSemaphore.acquire();
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

            } else if (autoRefreshData && isStale(cachedCandles, tf)) {
                // Need delta download - submit to parallel executor
                final List<Candle> cached = cachedCandles;  // For lambda
                downloadFutures.add(CompletableFuture.runAsync(() -> {
                    try {
                        downloadSemaphore.acquire();
                        activeDownloads.incrementAndGet();
                        
                        ZonedDateTime lastTimestamp = getLastTimestamp(cached);
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
                // Use cached data (no download needed)
                candlesByTimeframe.put(tf, cachedCandles);
            }
        }

        // Wait for all downloads to complete
        if (!downloadFutures.isEmpty()) {
            downloadLog.info("📥 {} downloading {} timeframes in parallel...", ticker, downloadFutures.size());
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
                    tradePlan = RiskCalculator.generatePlan(data, ticker, currentTime, !isCall, currentPrice);
                }

                Signal signal = new Signal(
                        ticker,
                        strategy.getName(),
                        isCall ? "CALL" : "PUT",
                        currentPrice,
                        nyTime,
                        tradePlan,
                        combinedPattern  // Include pattern in signal
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

    // ===== Data Freshness Checks =====

    private boolean isStale(List<Candle> candles, TimeFrame tf) {
        if (candles.isEmpty()) return true;
        ZonedDateTime lastTimestamp = candles.get(candles.size() - 1).timestamp();
        Duration threshold = FRESHNESS_THRESHOLDS.getOrDefault(tf, Duration.ofHours(2));
        Duration age = Duration.between(lastTimestamp, ZonedDateTime.now(ZoneId.of("America/New_York")));
        return age.compareTo(threshold) > 0;
    }

    private ZonedDateTime getLastTimestamp(List<Candle> candles) {
        return candles.get(candles.size() - 1).timestamp();
    }

    // ===== Helpers =====

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

    public record ScanResult(
            int totalSignals,
            int tickersScanned,
            List<Signal> signals,
            long elapsedMs
    ) {}

    public record Signal(
            String ticker,
            String strategy,
            String direction,
            double currentPrice,
            ZonedDateTime timestamp,
            TradePlan tradePlan,
            String candlestickPattern  // NEW: detected pattern (e.g., "squeeze_breakout + hammer")
    ) {
        // Backward-compatible compact constructor
        public Signal(String ticker, String strategy, String direction, double currentPrice,
                     ZonedDateTime timestamp, TradePlan tradePlan) {
            this(ticker, strategy, direction, currentPrice, timestamp, tradePlan, "unknown");
        }
    }
}
