package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.*;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import com.fgiaquinta.optionsquant.strategy.TradingStrategy;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.strategy.utils.CandlestickPatternDetector;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core backtest engine.
 *
 * Processes each ticker in parallel for significant speedup.
 * Each ticker runs as an independent mini-backtest with its own capital share,
 * then results are merged into a single BacktestReport.
 */
@Slf4j
@Service
public class BacktestEngine {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path CHECKPOINT_FILE = Path.of("backtest/checkpoint.txt");

    private final List<TradingStrategy> strategies;
    private final CandleCsvService csvService;
    private final TickerMemory tickerMemory;

    public BacktestEngine(CandleCsvService csvService, TickerMemory tickerMemory) {
        this.csvService = csvService;
        this.tickerMemory = tickerMemory;
        this.strategies = List.of(
                new com.fgiaquinta.optionsquant.strategy.C1SqueezeCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C2TrendCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C3BounceCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C4OpeningCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C5ContinuationCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C6ReversalCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P1SqueezePutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P2TrendPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P3BouncePutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P4OpeningPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P5ContinuationPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P6ReversalPutStrategy()
        );
    }

    /**
     * Saves a checkpoint of processed tickers.
     */
    public void saveCheckpoint(List<String> processedTickers) {
        try {
            Files.createDirectories(CHECKPOINT_FILE.getParent());
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(CHECKPOINT_FILE))) {
                pw.println("# Backtest checkpoint - processed tickers");
                pw.println("# " + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                for (String ticker : processedTickers) {
                    pw.println(ticker);
                }
            }
            log.info("💾 Checkpoint saved: {} tickers processed", processedTickers.size());
        } catch (Exception e) {
            log.warn("Failed to save checkpoint: {}", e.getMessage());
        }
    }

    /**
     * Loads the checkpoint of already-processed tickers.
     * Returns empty set if no checkpoint exists.
     */
    public Set<String> loadCheckpoint() {
        Set<String> processed = new LinkedHashSet<>();
        try {
            if (Files.exists(CHECKPOINT_FILE)) {
                List<String> lines = Files.readAllLines(CHECKPOINT_FILE);
                for (String line : lines) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    processed.add(line.trim());
                }
                log.info("📂 Checkpoint loaded: {} tickers already processed", processed.size());
            }
        } catch (Exception e) {
            log.warn("Failed to load checkpoint: {}", e.getMessage());
        }
        return processed;
    }

    /**
     * Clears the checkpoint file.
     */
    public void clearCheckpoint() {
        try {
            if (Files.exists(CHECKPOINT_FILE)) {
                Files.delete(CHECKPOINT_FILE);
                log.info("🗑️ Checkpoint cleared");
            }
        } catch (Exception e) {
            log.warn("Failed to clear checkpoint: {}", e.getMessage());
        }
    }

    /**
     * Checks if a checkpoint file exists.
     */
    public boolean hasCheckpoint() {
        return Files.exists(CHECKPOINT_FILE);
    }

    /**
     * Runs a backtest with the given configuration.
     * Processes each ticker in parallel for significant speedup.
     * Supports resume from checkpoint if previously saved.
     */
    public BacktestReport run(BacktestConfig config) {
        return run(config, false);
    }

    /**
     * Runs a backtest with the given configuration.
     * @param config The backtest configuration
     * @param resumeFromCheckpoint If true, loads checkpoint and skips already-processed tickers
     */
    public BacktestReport run(BacktestConfig config, boolean resumeFromCheckpoint) {
        return run(config, resumeFromCheckpoint, null);
    }

    /**
     * Runs a backtest with the given configuration.
     * @param config The backtest configuration
     * @param resumeFromCheckpoint If true, loads checkpoint and skips already-processed tickers
     * @param stopRequested Optional flag to check for early termination (set to true to stop)
     */
    public BacktestReport run(BacktestConfig config, boolean resumeFromCheckpoint, AtomicBoolean stopRequested) {
        log.info(">>> Backtest: tickers={}, {} to {}, capital=${}, risk={}%{}",
                config.tickers().size(), config.fromDate(), config.toDate(),
                config.initialCapital(), config.riskPerTradePct() * 100,
                resumeFromCheckpoint ? " (RESUME)" : "");

        long startTime = System.currentTimeMillis();

        // ============================================================
        // CHECKPOINT: Load already-processed tickers if resuming
        // ============================================================
        final Set<String> alreadyProcessed = new LinkedHashSet<>();
        List<TradeRecord> resumedTrades = new ArrayList<>();
        List<BacktestReport.EquityPoint> resumedEquity = new ArrayList<>();
        Map<String, TickerResult> resumedResults = new LinkedHashMap<>();

        if (resumeFromCheckpoint && hasCheckpoint()) {
            alreadyProcessed.addAll(loadCheckpoint());
            // Load existing trades from CSV for already-processed tickers
            resumedTrades = loadTradesForTickers(alreadyProcessed);
            resumedEquity = loadEquityCurve();
            log.info("📊 Resume mode: {} tickers already processed, {} trades loaded from CSV",
                    alreadyProcessed.size(), resumedTrades.size());
        }

        // Filter tickers to only those not yet processed
        List<String> remainingTickers = config.tickers().stream()
                .filter(t -> !alreadyProcessed.contains(t))
                .toList();

        if (remainingTickers.isEmpty() && !alreadyProcessed.isEmpty()) {
            log.info("✅ All tickers already processed! Loading full results from checkpoint...");
            return buildReportFromResumedData(config, resumedTrades, resumedEquity, startTime);
        }

        log.info("🔄 Processing {} new tickers ({} already done)", remainingTickers.size(), alreadyProcessed.size());

        // Async CSV writer using BlockingQueue + dedicated writer thread
        LinkedBlockingQueue<TradeRecord> tradeQueue = new LinkedBlockingQueue<>(10000);
        AtomicBoolean writerDone = new AtomicBoolean(false);
        Path tradesCsvPath = Path.of("backtest/trades.csv");

        // Dedicated CSV writer thread
        Thread csvWriterThread = new Thread(() -> {
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(tradesCsvPath, StandardOpenOption.APPEND)) {
                List<TradeRecord> batch = new ArrayList<>(100);
                while (!writerDone.get() || !tradeQueue.isEmpty()) {
                    TradeRecord trade = tradeQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (trade != null) {
                        batch.add(trade);
                        tradeQueue.drainTo(batch);
                        for (TradeRecord t : batch) {
                            writer.write(formatTradeCsv(t));
                            writer.newLine();
                        }
                        batch.clear();
                    }
                }
                // Write remaining
                tradeQueue.drainTo(batch);
                for (TradeRecord t : batch) {
                    writer.write(formatTradeCsv(t));
                    writer.newLine();
                }
            } catch (Exception e) {
                log.error("CSV writer thread error: {}", e.getMessage());
            }
        }, "backtest-csv-writer");
        csvWriterThread.setDaemon(true);
        csvWriterThread.start();

        // Only clear CSV and write header if starting fresh (not resuming)
        if (!resumeFromCheckpoint || !hasCheckpoint()) {
            try {
                Files.createDirectories(Path.of("backtest"));
                Files.deleteIfExists(tradesCsvPath);
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(tradesCsvPath, StandardOpenOption.CREATE))) {
                    pw.println("Ticker,Strategy,Direction,Qty,EntryPrice,EntryTime,ExitPrice,ExitTime,ExitReason,GrossPnl,Commission,Slippage,NetPnl,MaxDD,MaxRunup,Pattern,ATR,VIX,EntryHour,MarketTrend");
                }
            } catch (Exception e) {
                log.warn("Could not initialize CSV output: {}", e.getMessage());
            }
        }

        // ============================================================
        // STEP 1: Load candle data in parallel
        // ============================================================
        int numThreads = Math.min(Runtime.getRuntime().availableProcessors(), Math.max(1, remainingTickers.size()));
        Map<String, Map<TimeFrame, List<Candle>>> allData = new ConcurrentHashMap<>();
        AtomicInteger loadedCount = new AtomicInteger(0);

        try (ExecutorService loadExecutor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r);
            t.setName("backtest-loader-" + t.threadId());
            t.setDaemon(true);
            return t;
        })) {
            List<CompletableFuture<Void>> loadFutures = config.tickers().stream()
                .map(ticker -> CompletableFuture.runAsync(() -> {
                    try {
                        Map<TimeFrame, List<Candle>> tickerData = new EnumMap<>(TimeFrame.class);
                        for (TimeFrame tf : TimeFrame.values()) {
                            List<Candle> candles = csvService.loadFromCsv(ticker, tf);
                            List<Candle> filtered = candles.stream()
                                    .filter(c -> !c.timestamp().toLocalDate().isBefore(config.fromDate())
                                            && !c.timestamp().toLocalDate().isAfter(config.toDate()))
                                    .toList();
                            tickerData.put(tf, filtered);
                        }
                        allData.put(ticker, tickerData);
                        int count = loadedCount.incrementAndGet();
                        if (count % 50 == 0 || count == config.tickers().size()) {
                            log.info("Loaded candle data for {}/{} tickers...", count, config.tickers().size());
                        }
                    } catch (Exception e) {
                        log.error("Failed to load data for {}: {}", ticker, e.getMessage());
                        allData.put(ticker, new EnumMap<>(TimeFrame.class));
                    }
                }, loadExecutor))
                .toList();
            CompletableFuture.allOf(loadFutures.toArray(new CompletableFuture<?>[0])).join();
        }

        TimeFrame execTf = config.executionTimeframe();

        // Count total execution candles for logging
        int totalExecCandles = allData.values().stream()
                .map(td -> td.getOrDefault(execTf, List.of()))
                .mapToInt(List::size)
                .sum();
        log.info("Loaded {} execution candles across {} tickers", totalExecCandles, config.tickers().size());

        // ============================================================
        // STEP 2: Process each ticker in parallel
        // ============================================================
        double capitalPerTicker = config.initialCapital() / config.tickers().size();
        Map<String, TickerResult> tickerResults = new ConcurrentHashMap<>();
        AtomicInteger processedCount = new AtomicInteger(0);
        List<String> newlyProcessed = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService processExecutor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r);
            t.setName("backtest-worker-" + t.threadId());
            t.setDaemon(true);
            return t;
        })) {
            List<CompletableFuture<Void>> processFutures = remainingTickers.stream()
                .map(ticker -> CompletableFuture.runAsync(() -> {
                    // Check stop requested before processing each ticker
                    if (stopRequested != null && stopRequested.get()) {
                        log.info("Backtest stop requested - skipping ticker {}", ticker);
                        return;
                    }
                    try {
                        int candleCount = allData.getOrDefault(ticker, Map.of())
                                .getOrDefault(execTf, List.of()).size();
                        int idx = processedCount.incrementAndGet();
                        int totalRemaining = remainingTickers.size();
                        log.info("[{}/{}] Processing ticker: {} ({} candles)", idx, totalRemaining, ticker, candleCount);

                        TickerResult result = processSingleTicker(
                                ticker, config, allData, capitalPerTicker, tradeQueue);

                        tickerResults.put(ticker, result);
                        newlyProcessed.add(ticker);

                        // Save checkpoint every 50 tickers (not after every single one to avoid O(N^2) copies)
                        int newlyProcessedCount = newlyProcessed.size();
                        if (newlyProcessedCount % 50 == 0 || newlyProcessedCount == remainingTickers.size()) {
                            Set<String> allProcessedNow = new LinkedHashSet<>(alreadyProcessed);
                            allProcessedNow.addAll(newlyProcessed);
                            saveCheckpoint(new ArrayList<>(allProcessedNow));
                        }

                        log.info("[{}/{}] Completed ticker: {} - {} trades, PnL=${:.2f}",
                                idx, totalRemaining, ticker, result.trades.size(),
                                result.trades.stream().mapToDouble(TradeRecord::netPnl).sum());
                    } catch (Exception e) {
                        log.error("Failed to process ticker {}: {}", ticker, e.getMessage(), e);
                        tickerResults.put(ticker, new TickerResult(List.of(), List.of(), 0));
                    }
                }, processExecutor))
                .toList();
            CompletableFuture.allOf(processFutures.toArray(new CompletableFuture<?>[0])).join();
        }

        // Signal CSV writer thread to finish and wait for completion
        writerDone.set(true);
        try {
            csvWriterThread.join(10000); // Wait up to 10 seconds
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for CSV writer thread");
            Thread.currentThread().interrupt();
        }

        // ============================================================
        // STEP 3: Merge results into final report
        // ============================================================
        long elapsed = System.currentTimeMillis() - startTime;

        // Merge resumed trades with newly processed trades
        List<TradeRecord> newTrades = tickerResults.values().stream()
                .flatMap(r -> r.trades.stream())
                .toList();
        List<TradeRecord> allTrades = new ArrayList<>();
        allTrades.addAll(resumedTrades);
        allTrades.addAll(newTrades);
        allTrades = allTrades.stream()
                .sorted(Comparator.comparing(TradeRecord::entryTime))
                .toList();

        // Merge equity curves
        List<BacktestReport.EquityPoint> newEquity = tickerResults.values().stream()
                .flatMap(r -> r.equityCurve.stream())
                .toList();
        List<BacktestReport.EquityPoint> combinedEquity = new ArrayList<>();
        combinedEquity.addAll(resumedEquity);
        combinedEquity.addAll(newEquity);
        combinedEquity = combinedEquity.stream()
                .sorted(Comparator.comparing(p -> p.timestamp()))
                .toList();

        // Single-pass statistics computation
        double totalPnl = 0, totalProfit = 0, totalLoss = 0, sharpeSum = 0, sharpeSqSum = 0;
        int wins = 0, losses = 0;
        double avgDurationSum = 0;
        Map<String, int[]> strategyCounts = new HashMap<>();      // [trades, wins]
        Map<String, double[]> strategyPnl = new HashMap<>();       // [profit, loss]
        Map<String, int[]> tickerCounts = new HashMap<>();         // [trades, wins]
        Map<String, double[]> tickerPnl = new HashMap<>();         // [profit, loss]

        for (TradeRecord t : allTrades) {
            double pnl = t.netPnl();
            totalPnl += pnl;

            if (pnl > 0) {
                wins++;
                totalProfit += pnl;
                strategyPnl.computeIfAbsent(t.strategy(), k -> new double[2])[0] += pnl;
                tickerPnl.computeIfAbsent(t.ticker(), k -> new double[2])[0] += pnl;
            } else if (pnl < 0) {
                losses++;
                double absLoss = Math.abs(pnl);
                totalLoss += absLoss;
                strategyPnl.computeIfAbsent(t.strategy(), k -> new double[2])[1] += absLoss;
                tickerPnl.computeIfAbsent(t.ticker(), k -> new double[2])[1] += absLoss;
            }

            strategyCounts.computeIfAbsent(t.strategy(), k -> new int[2])[0]++;
            if (pnl > 0) strategyCounts.get(t.strategy())[1]++;

            tickerCounts.computeIfAbsent(t.ticker(), k -> new int[2])[0]++;
            if (pnl > 0) tickerCounts.get(t.ticker())[1]++;

            avgDurationSum += java.time.Duration.between(t.entryTime(), t.exitTime()).toMinutes() / 60.0;

            // Sharpe components
            double ret = pnl / config.initialCapital();
            sharpeSum += ret;
            sharpeSqSum += ret * ret;
        }

        double finalCapital = config.initialCapital() + totalPnl;
        double winRate = allTrades.isEmpty() ? 0 : (double) wins / allTrades.size();
        double profitFactor = totalLoss == 0 ? (totalProfit > 0 ? Double.POSITIVE_INFINITY : 0) : totalProfit / totalLoss;
        double avgWin = wins > 0 ? totalProfit / wins : 0;
        double avgLoss = losses > 0 ? totalLoss / losses : 0;
        double avgDuration = allTrades.isEmpty() ? 0 : avgDurationSum / allTrades.size();

        // Sharpe ratio
        double n = allTrades.size();
        double sharpe = n < 2 ? 0 : (sharpeSum / n) / Math.sqrt((sharpeSqSum / n) - (sharpeSum / n) * (sharpeSum / n) + 1e-10) * Math.sqrt(252);

        // Max drawdown from equity curve
        double maxDrawdown = 0, maxDrawdownPct = 0;
        double peak = config.initialCapital();
        for (BacktestReport.EquityPoint p : combinedEquity) {
            if (p.equity() > peak) peak = p.equity();
            double dd = peak - p.equity();
            if (dd > maxDrawdown) { maxDrawdown = dd; maxDrawdownPct = dd / peak; }
        }

        // Build byStrategy stats
        Map<String, BacktestReport.StrategyStats> byStrategy = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : strategyCounts.entrySet()) {
            String name = e.getKey();
            int[] counts = e.getValue();
            double[] pnl = strategyPnl.getOrDefault(name, new double[2]);
            double pf = pnl[1] == 0 ? (pnl[0] > 0 ? Double.POSITIVE_INFINITY : 0) : pnl[0] / pnl[1];
            byStrategy.put(name, new BacktestReport.StrategyStats(counts[0], counts[1],
                    counts[0] == 0 ? 0 : (double) counts[1] / counts[0], pnl[0] - pnl[1], pf, 0));
        }

        // Build byTicker stats
        Map<String, BacktestReport.StrategyStats> byTicker = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : tickerCounts.entrySet()) {
            String name = e.getKey();
            int[] counts = e.getValue();
            double[] pnl = tickerPnl.getOrDefault(name, new double[2]);
            double pf = pnl[1] == 0 ? (pnl[0] > 0 ? Double.POSITIVE_INFINITY : 0) : pnl[0] / pnl[1];
            byTicker.put(name, new BacktestReport.StrategyStats(counts[0], counts[1],
                    counts[0] == 0 ? 0 : (double) counts[1] / counts[0], pnl[0] - pnl[1], pf, 0));
        }

        BacktestReport report = new BacktestReport(
                config.initialCapital(), finalCapital, totalPnl,
                totalPnl / config.initialCapital(),
                allTrades.size(), wins, losses, winRate, profitFactor,
                maxDrawdown, maxDrawdownPct, sharpe, avgWin, avgLoss, avgDuration,
                byStrategy, byTicker, combinedEquity, allTrades, elapsed);

        // Write summary and equity CSV
        writeSummaryReport(report);
        writeEquityCsv(combinedEquity);

        // Clear checkpoint on successful completion
        clearCheckpoint();

        log.info("<<< Backtest complete: equity=${:.2f}, elapsed={}ms ({:.1f}s), {} new + {} resumed trades",
                finalCapital, elapsed, elapsed / 1000.0, newTrades.size(), resumedTrades.size());
        return report;
    }

    /**
     * Processes a single ticker's candles sequentially, maintaining its own state.
     * This method is designed to be called from parallel threads.
     */
    private TickerResult processSingleTicker(String ticker, BacktestConfig config,
            Map<String, Map<TimeFrame, List<Candle>>> allData, double initialCapital,
            LinkedBlockingQueue<TradeRecord> tradeQueue) {

        FillEngine fillEngine = new SimulatedFillEngine(config.slippagePct(), config.commissionPerContract());
        TimeFrame execTf = config.executionTimeframe();

        List<Candle> execCandles = allData.getOrDefault(ticker, Map.of())
                .getOrDefault(execTf, List.of());
        if (execCandles.isEmpty()) {
            return new TickerResult(List.of(), List.of(), 0);
        }

        double equity = initialCapital;
        List<OpenPosition> openPositions = new ArrayList<>();
        List<TradeRecord> trades = Collections.synchronizedList(new ArrayList<>());
        List<BacktestReport.EquityPoint> equityCurve = Collections.synchronizedList(new ArrayList<>());
        double peakEquity = equity;

        // Build StrategyData ONCE with full candle data for this ticker
        Map<TimeFrame, List<Candle>> fullTickerData = allData.get(ticker);
        if (fullTickerData == null || fullTickerData.isEmpty()) {
            return new TickerResult(List.of(), List.of(), 0);
        }
        StrategyData fullData = new StrategyData(fullTickerData);

        for (Candle currentCandle : execCandles) {
            ZonedDateTime candleTime = currentCandle.timestamp();

            // Use the pre-built full StrategyData - strategies use getIndexForTime()
            // which ensures they only access bars up to the current candleTime,
            // so no look-ahead bias occurs despite having all bars available.
            StrategyData data = fullData;

            // Check exits for open positions
            Iterator<OpenPosition> it = openPositions.iterator();
            while (it.hasNext()) {
                OpenPosition pos = it.next();
                boolean tpHit = pos.isCall ? currentCandle.high() >= pos.tp : currentCandle.low() <= pos.tp;
                boolean slHit = pos.isCall ? currentCandle.low() <= pos.sl : currentCandle.high() >= pos.sl;

                if (tpHit || slHit) {
                    double exitPrice = tpHit ? pos.tp : pos.sl;
                    String exitReason = tpHit ? "TP" : "SL";
                    TradeRecord trade = closePosition(ticker, pos, exitPrice, candleTime, exitReason, fillEngine);
                    trades.add(trade);
                    // Non-blocking queue offer for async CSV writing
                    tradeQueue.offer(trade);
                    it.remove();
                    continue;
                }

                // Track max runup/drawdown
                if (pos.isCall) {
                    pos.maxRunup = Math.max(pos.maxRunup, (currentCandle.high() - pos.entryPrice) * pos.quantity * 100);
                    pos.maxDrawdown = Math.max(pos.maxDrawdown, (pos.entryPrice - currentCandle.low()) * pos.quantity * 100);
                } else {
                    pos.maxRunup = Math.max(pos.maxRunup, (pos.entryPrice - currentCandle.low()) * pos.quantity * 100);
                    pos.maxDrawdown = Math.max(pos.maxDrawdown, (currentCandle.high() - pos.entryPrice) * pos.quantity * 100);
                }
            }

            // Update equity with unrealized
            equity = calculateEquity(equity, openPositions, currentCandle);
            if (equity > peakEquity) peakEquity = equity;

            // Downsample equity curve: only record one point per hour
            ZonedDateTime lastRecorded = equityCurve.isEmpty() ? null : equityCurve.get(equityCurve.size() - 1).timestamp();
            if (lastRecorded == null || !candleTime.toLocalDate().equals(lastRecorded.toLocalDate())
                    || candleTime.getHour() != lastRecorded.getHour()) {
                equityCurve.add(new BacktestReport.EquityPoint(candleTime, equity));
            }

            // Run strategies if we have room
            if (openPositions.size() < config.maxConcurrentTrades()) {
                ZonedDateTime nyTime = candleTime.withZoneSameInstant(NY);
                runStrategies(ticker, data, nyTime, currentCandle, candleTime,
                        config, equity, openPositions, fillEngine);
            }
        }

        // Close remaining positions at last candle
        Candle lastCandle = execCandles.get(execCandles.size() - 1);
        for (OpenPosition pos : new ArrayList<>(openPositions)) {
            TradeRecord trade = closePosition(ticker, pos, lastCandle.close(), lastCandle.timestamp(), "EOS", fillEngine);
            trades.add(trade);
            tradeQueue.offer(trade);
        }
        openPositions.clear();

        return new TickerResult(trades, equityCurve, equity);
    }

    /**
     * Formats a TradeRecord as a CSV line string.
     */
    private String formatTradeCsv(TradeRecord trade) {
        return String.format(Locale.US, "%s,%s,%s,%d,%.2f,%s,%.2f,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%.2f,%.2f,%d,%s",
                trade.ticker(), trade.strategy(), trade.direction(), trade.quantity(),
                trade.entryPrice(), trade.entryTime().format(TS_FMT),
                trade.exitPrice(), trade.exitTime().format(TS_FMT),
                trade.exitReason(), trade.grossPnl(), trade.commission(),
                trade.slippage(), trade.netPnl(), trade.maxDrawdown(), trade.maxRunup(),
                trade.candlestickPattern(), trade.atrAtEntry(), trade.vixAtEntry(),
                trade.entryHour(), trade.marketTrend());
    }

    /**
     * Writes the equity curve to CSV.
     */
    private void writeEquityCsv(List<BacktestReport.EquityPoint> equityCurve) {
        Path filePath = Path.of("backtest/equity.csv");
        try {
            Files.createDirectories(Path.of("backtest"));
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath))) {
                pw.println("Timestamp,Equity");
                for (BacktestReport.EquityPoint p : equityCurve) {
                    pw.printf(Locale.US, "%s,%.2f%n", p.timestamp().format(TS_FMT), p.equity());
                }
            }
        } catch (Exception e) {
            log.error("Failed to write equity CSV: {}", e.getMessage(), e);
        }
    }

    /**
     * Loads trades from CSV for already-processed tickers (resume support).
     */
    private List<TradeRecord> loadTradesForTickers(Set<String> tickers) {
        List<TradeRecord> trades = new ArrayList<>();
        Path csvPath = Path.of("backtest/trades.csv");
        if (!Files.exists(csvPath)) return trades;

        try {
            List<String> lines = Files.readAllLines(csvPath);
            if (lines.isEmpty()) return trades;

            for (int i = 1; i < lines.size(); i++) { // Skip header
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 20) {
                    String ticker = parts[0].trim();
                    if (tickers.contains(ticker)) {
                        TradeRecord trade = new TradeRecord(
                                ticker, parts[1].trim(), parts[2].trim(),
                                Integer.parseInt(parts[3].trim()),
                                Double.parseDouble(parts[4].trim()),
                                ZonedDateTime.parse(parts[5].trim(), TS_FMT),
                                Double.parseDouble(parts[6].trim()),
                                ZonedDateTime.parse(parts[7].trim(), TS_FMT),
                                parts[8].trim(),
                                Double.parseDouble(parts[9].trim()),
                                Double.parseDouble(parts[10].trim()),
                                Double.parseDouble(parts[11].trim()),
                                Double.parseDouble(parts[12].trim()),
                                Double.parseDouble(parts[13].trim()),
                                Double.parseDouble(parts[14].trim()),
                                parts[15].trim(),
                                Double.parseDouble(parts[16].trim()),
                                Double.parseDouble(parts[17].trim()),
                                Integer.parseInt(parts[18].trim()),
                                parts[19].trim(),
                                new HashMap<>()
                        );
                        trades.add(trade);
                    }
                }
            }
            log.debug("Loaded {} resumed trades for {} tickers", trades.size(), tickers.size());
        } catch (Exception e) {
            log.warn("Failed to load resumed trades: {}", e.getMessage());
        }
        return trades;
    }

    /**
     * Loads the equity curve from CSV (resume support).
     */
    private List<BacktestReport.EquityPoint> loadEquityCurve() {
        List<BacktestReport.EquityPoint> equity = new ArrayList<>();
        Path csvPath = Path.of("backtest/equity.csv");
        if (!Files.exists(csvPath)) return equity;

        try {
            List<String> lines = Files.readAllLines(csvPath);
            if (lines.isEmpty()) return equity;

            for (int i = 1; i < lines.size(); i++) { // Skip header
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    equity.add(new BacktestReport.EquityPoint(
                            ZonedDateTime.parse(parts[0].trim(), TS_FMT),
                            Double.parseDouble(parts[1].trim())
                    ));
                }
            }
            log.debug("Loaded {} equity points from CSV", equity.size());
        } catch (Exception e) {
            log.warn("Failed to load equity curve: {}", e.getMessage());
        }
        return equity;
    }

    /**
     * Builds a BacktestReport entirely from resumed data (when all tickers already processed).
     */
    private BacktestReport buildReportFromResumedData(BacktestConfig config,
            List<TradeRecord> allTrades, List<BacktestReport.EquityPoint> equityCurve, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;

        // Single-pass statistics computation
        double localTotalPnl = 0, localTotalProfit = 0, localTotalLoss = 0, localSharpeSum = 0, localSharpeSqSum = 0;
        int localWins = 0, localLosses = 0;
        double localAvgDurationSum = 0;
        Map<String, int[]> localStrategyCounts = new HashMap<>();
        Map<String, double[]> localStrategyPnl = new HashMap<>();
        Map<String, int[]> localTickerCounts = new HashMap<>();
        Map<String, double[]> localTickerPnl = new HashMap<>();

        for (TradeRecord t : allTrades) {
            double pnl = t.netPnl();
            localTotalPnl += pnl;
            if (pnl > 0) {
                localWins++;
                localTotalProfit += pnl;
                localStrategyPnl.computeIfAbsent(t.strategy(), k -> new double[2])[0] += pnl;
                localTickerPnl.computeIfAbsent(t.ticker(), k -> new double[2])[0] += pnl;
            } else if (pnl < 0) {
                localLosses++;
                double absLoss = Math.abs(pnl);
                localTotalLoss += absLoss;
                localStrategyPnl.computeIfAbsent(t.strategy(), k -> new double[2])[1] += absLoss;
                localTickerPnl.computeIfAbsent(t.ticker(), k -> new double[2])[1] += absLoss;
            }
            localStrategyCounts.computeIfAbsent(t.strategy(), k -> new int[2])[0]++;
            if (pnl > 0) localStrategyCounts.get(t.strategy())[1]++;
            localTickerCounts.computeIfAbsent(t.ticker(), k -> new int[2])[0]++;
            if (pnl > 0) localTickerCounts.get(t.ticker())[1]++;
            localAvgDurationSum += java.time.Duration.between(t.entryTime(), t.exitTime()).toMinutes() / 60.0;
            double ret = pnl / config.initialCapital();
            localSharpeSum += ret;
            localSharpeSqSum += ret * ret;
        }

        double finalCapital = config.initialCapital() + localTotalPnl;
        double winRate = allTrades.isEmpty() ? 0 : (double) localWins / allTrades.size();
        double profitFactor = localTotalLoss == 0 ? (localTotalProfit > 0 ? Double.POSITIVE_INFINITY : 0) : localTotalProfit / localTotalLoss;
        double avgWin = localWins > 0 ? localTotalProfit / localWins : 0;
        double avgLoss = localLosses > 0 ? localTotalLoss / localLosses : 0;
        double avgDuration = allTrades.isEmpty() ? 0 : localAvgDurationSum / allTrades.size();
        double n = allTrades.size();
        double sharpe = n < 2 ? 0 : (localSharpeSum / n) / Math.sqrt((localSharpeSqSum / n) - (localSharpeSum / n) * (localSharpeSum / n) + 1e-10) * Math.sqrt(252);

        double maxDrawdown = 0, maxDrawdownPct = 0;
        double peak = config.initialCapital();
        for (BacktestReport.EquityPoint p : equityCurve) {
            if (p.equity() > peak) peak = p.equity();
            double dd = peak - p.equity();
            if (dd > maxDrawdown) { maxDrawdown = dd; maxDrawdownPct = dd / peak; }
        }

        Map<String, BacktestReport.StrategyStats> byStrategy = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : localStrategyCounts.entrySet()) {
            String name = e.getKey();
            int[] counts = e.getValue();
            double[] pnl = localStrategyPnl.getOrDefault(name, new double[2]);
            double pf = pnl[1] == 0 ? (pnl[0] > 0 ? Double.POSITIVE_INFINITY : 0) : pnl[0] / pnl[1];
            byStrategy.put(name, new BacktestReport.StrategyStats(counts[0], counts[1],
                    counts[0] == 0 ? 0 : (double) counts[1] / counts[0], pnl[0] - pnl[1], pf, 0));
        }
        Map<String, BacktestReport.StrategyStats> byTicker = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : localTickerCounts.entrySet()) {
            String name = e.getKey();
            int[] counts = e.getValue();
            double[] pnl = localTickerPnl.getOrDefault(name, new double[2]);
            double pf = pnl[1] == 0 ? (pnl[0] > 0 ? Double.POSITIVE_INFINITY : 0) : pnl[0] / pnl[1];
            byTicker.put(name, new BacktestReport.StrategyStats(counts[0], counts[1],
                    counts[0] == 0 ? 0 : (double) counts[1] / counts[0], pnl[0] - pnl[1], pf, 0));
        }

        BacktestReport report = new BacktestReport(
                config.initialCapital(), finalCapital, localTotalPnl,
                localTotalPnl / config.initialCapital(),
                allTrades.size(), localWins, localLosses, winRate, profitFactor,
                maxDrawdown, maxDrawdownPct, sharpe, avgWin, avgLoss, avgDuration,
                byStrategy, byTicker, equityCurve, allTrades, elapsed);

        writeSummaryReport(report);
        writeEquityCsv(equityCurve);
        clearCheckpoint();

        log.info("<<< Backtest resumed: equity=${:.2f}, {} total trades (all from checkpoint)", finalCapital, allTrades.size());
        return report;
    }

    /**
     * Writes the summary report to a text file.
     */
    private void writeSummaryReport(BacktestReport r) {
        Path filePath = Path.of("backtest/summary.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath))) {
            pw.println("=== BACKTEST SUMMARY ===");
            pw.printf("Initial Capital: $%.2f%n", r.initialCapital());
            pw.printf("Final Capital: $%.2f%n", r.finalCapital());
            pw.printf("Total Return: $%.2f (%.2f%%)%n", r.totalReturn(), r.totalReturnPct() * 100);
            pw.printf("Trades: %d (Win: %d, Loss: %d, Rate: %.1f%%)%n", r.totalTrades(), r.winningTrades(), r.losingTrades(), r.winRate() * 100);
            pw.printf("Profit Factor: %.2f%n", r.profitFactor());
            pw.printf("Max Drawdown: $%.2f (%.2f%%)%n", r.maxDrawdown(), r.maxDrawdownPct() * 100);
            pw.printf("Avg Win: $%.2f | Avg Loss: $%.2f%n", r.avgWin(), r.avgLoss());
            pw.printf("Sharpe Ratio: %.2f%n", r.sharpeRatio());
            pw.println("\n=== BY STRATEGY ===");
            r.byStrategy().forEach((name, s) ->
                    pw.printf("%-30s | Trades: %4d | Win: %.1f%% | PnL: $%8.2f | PF: %.2f%n",
                            name, s.trades(), s.winRate() * 100, s.totalPnl(), s.profitFactor()));
            pw.println("\n=== BY TICKER ===");
            r.byTicker().forEach((t, s) ->
                    pw.printf("%-10s | Trades: %4d | Win: %.1f%% | PnL: $%8.2f | PF: %.2f%n",
                            t, s.trades(), s.winRate() * 100, s.totalPnl(), s.profitFactor()));
            pw.printf("\nElapsed: %d ms%n", r.elapsedMs());
        } catch (Exception e) {
            log.warn("Failed to write summary: {}", e.getMessage());
        }
    }

    /**
     * Calculates unrealized equity from cash and open positions.
     */
    private double calculateEquity(double cash, List<OpenPosition> openPositions, Candle candle) {
        double unrealized = 0;
        for (OpenPosition pos : openPositions) {
            if (pos.isCall) {
                unrealized += (candle.close() - pos.entryPrice) * pos.quantity * 100;
            } else {
                unrealized += (pos.entryPrice - candle.close()) * pos.quantity * 100;
            }
        }
        return cash + unrealized;
    }

    /**
     * Runs all strategies for a given candle. New positions are added directly
     * to openPositions. TradeRecords are created at exit time.
     */
    private void runStrategies(String ticker, StrategyData data, ZonedDateTime nyTime,
            Candle candle, ZonedDateTime time, BacktestConfig config, double equity,
            List<OpenPosition> openPositions, FillEngine fillEngine) {

        for (TradingStrategy strategy : strategies) {
            try {
                if (!strategy.isTriggered(ticker, data, nyTime)) continue;

                boolean isCall = strategy.getName().contains("call");
                double entryPrice = candle.close();

                TradePlan plan = RiskCalculator.generatePlan(data, ticker, time, isCall, entryPrice, strategy.getName());
                double riskPerContract = Math.abs(entryPrice - plan.stopLoss) * 100;
                double maxRisk = equity * config.riskPerTradePct();

                int qty = riskPerContract > 0 ? (int) Math.floor(maxRisk / riskPerContract) : 0;
                qty = Math.max(1, Math.min(qty, 10));

                FillResult entryFill = fillEngine.fillEntry(ticker, isCall ? "CALL" : "PUT", qty, plan, time);

                String candlestickPattern = detectEntryCandlestickPattern(data, config.executionTimeframe());
                String strategyPattern = extractPatternFromStrategy(strategy.getName());
                String combinedPattern = strategyPattern + " + " + candlestickPattern;

                List<Candle> chartCandles = data.getCandles(config.executionTimeframe());

                OpenPosition pos = new OpenPosition(
                        strategy.getName(), isCall ? "CALL" : "PUT", combinedPattern, qty,
                        entryFill.fillPrice(), plan.takeProfit, plan.stopLoss, time,
                        plan.atr, chartCandles);
                openPositions.add(pos);

                // Fix #13: Chart generation deferred to on-demand only.
                // Chart data (chartCandles) is stored in OpenPosition and will be used
                // to generate charts only when explicitly requested via API after backtest completes.
                // This avoids significant temporary string objects and disk I/O during scanning.
                /*
                if (chartCandles != null && !chartCandles.isEmpty()) {
                    Path chartsDir = Path.of("backtest/charts");
                    Path chartPath = SignalChartGenerator.generateChart(
                            ticker, strategy.getName(), candle.timestamp(),
                            entryFill.fillPrice(), plan.takeProfit, plan.stopLoss,
                            isCall, chartCandles, chartsDir,
                            null, null, null);
                    if (chartPath != null) {
                        log.debug("Chart generated: {}", chartPath);
                    }
                }
                */

                log.debug("Signal: {} {} at {} entry={} qty={} pattern={}", ticker, pos.direction,
                        time.format(TS_FMT), entryFill.fillPrice(), qty, candlestickPattern);
            } catch (Exception e) {
                log.debug("Strategy {} error for {}: {} (type: {})",
                        strategy.getName(), ticker, e.getMessage(),
                        e.getClass().getSimpleName());
                if (log.isTraceEnabled()) {
                    log.trace("Full stack trace for strategy error:", e);
                }
            }
        }
    }

    /**
     * Detects the actual candlestick pattern at the entry candle.
     */
    private String detectEntryCandlestickPattern(StrategyData data, TimeFrame executionTimeframe) {
        try {
            org.ta4j.core.BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
            if (series1h == null || series1h.isEmpty()) {
                return "unknown";
            }
            int lastIndex = series1h.getEndIndex();
            String pattern = CandlestickPatternDetector.detectPattern(series1h, lastIndex);
            return pattern;
        } catch (Exception e) {
            log.debug("Failed to detect candlestick pattern: {}", e.getMessage());
            return "unknown";
        }
    }

    /**
     * Closes a position and returns the completed TradeRecord.
     */
    private TradeRecord closePosition(String ticker, OpenPosition pos, double exitPrice,
            ZonedDateTime exitTime, String reason, FillEngine fillEngine) {
        FillResult exitFill = fillEngine.fillExit(ticker, pos.direction, pos.quantity, exitPrice, exitTime);
        double grossPnl = pos.isCall
                ? (exitPrice - pos.entryPrice) * pos.quantity * 100
                : (pos.entryPrice - exitPrice) * pos.quantity * 100;
        double netPnl = grossPnl - exitFill.commission() - exitFill.slippage() * pos.quantity * 100;

        TradeRecord trade = new TradeRecord(
                ticker, pos.strategy, pos.direction, pos.quantity,
                pos.entryPrice, pos.entryTime, exitPrice, exitTime, reason,
                grossPnl, exitFill.commission(), exitFill.slippage(),
                netPnl, pos.maxDrawdown, pos.maxRunup,
                pos.pattern, pos.atrAtEntry, pos.vixAtEntry, pos.entryHour,
                pos.marketTrend, Map.of());

        boolean isWin = netPnl > 0;
        tickerMemory.recordTrade(
                ticker, pos.strategy, pos.pattern, isWin, netPnl,
                pos.maxDrawdown, pos.maxRunup, pos.atrAtEntry, pos.vixAtEntry,
                pos.entryHour, pos.marketTrend,
                Map.of("exitReason", reason, "grossPnl", grossPnl));

        // Fix #13: Chart generation deferred to on-demand only.
        // Chart data (pos.chartCandles) is preserved in TradeRecord for on-demand generation.
        // Charts will be generated only when explicitly requested via API after backtest completes.
        /*
        if (pos.chartCandles != null && !pos.chartCandles.isEmpty()) {
            long minutesHeld = java.time.Duration.between(pos.entryTime, exitTime).toMinutes();
            int candlesHeld = Math.max(1, (int) Math.round(minutesHeld / 15.0));

            Path chartsDir = Path.of("backtest/charts");
            SignalChartGenerator.generateChart(
                    ticker, pos.strategy, pos.entryTime,
                    pos.entryPrice, pos.tp, pos.sl,
                    pos.isCall, pos.chartCandles, chartsDir,
                    netPnl, reason, candlesHeld);
        }
        */

        return trade;
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

    /**
     * Result of processing a single ticker - used to merge parallel results.
     */
    private record TickerResult(
            List<TradeRecord> trades,
            List<BacktestReport.EquityPoint> equityCurve,
            double finalEquity
    ) {}

    private static class OpenPosition {
        final String strategy;
        final String direction;
        final String pattern;
        final int quantity;
        final double entryPrice;
        final double tp;
        final double sl;
        final ZonedDateTime entryTime;
        final int entryHour;
        final double atrAtEntry;
        final double vixAtEntry;
        final String marketTrend;
        final List<Candle> chartCandles;
        double maxDrawdown = 0;
        double maxRunup = 0;
        final boolean isCall;

        OpenPosition(String strategy, String direction, String pattern, int quantity, double entryPrice,
                     double tp, double sl, ZonedDateTime entryTime, double atrAtEntry,
                     List<Candle> chartCandles) {
            this.strategy = strategy;
            this.direction = direction;
            this.pattern = pattern;
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.tp = tp;
            this.sl = sl;
            this.entryTime = entryTime;
            this.entryHour = entryTime.withZoneSameInstant(NY).getHour();
            this.atrAtEntry = atrAtEntry;
            this.vixAtEntry = 0.0;
            this.marketTrend = "neutral";
            this.isCall = direction.equalsIgnoreCase("CALL");
            this.chartCandles = chartCandles;
        }
    }
}
