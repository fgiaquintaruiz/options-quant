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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

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
     * Runs a backtest with the given configuration.
     * Processes each ticker in parallel for significant speedup.
     */
    public BacktestReport run(BacktestConfig config) {
        log.info(">>> Backtest: tickers={}, {} to {}, capital=${}, risk={}%",
                config.tickers().size(), config.fromDate(), config.toDate(),
                config.initialCapital(), config.riskPerTradePct() * 100);

        long startTime = System.currentTimeMillis();

        // Shared CSV writer lock for thread-safe trade recording
        ReentrantLock csvLock = new ReentrantLock();
        Path tradesCsvPath = Path.of("backtest/trades.csv");

        // Create output directory and write CSV header
        try {
            Files.createDirectories(Path.of("backtest"));
            Files.deleteIfExists(tradesCsvPath);
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(tradesCsvPath, StandardOpenOption.CREATE))) {
                pw.println("Ticker,Strategy,Direction,Qty,EntryPrice,EntryTime,ExitPrice,ExitTime,ExitReason,GrossPnl,Commission,Slippage,NetPnl,MaxDD,MaxRunup,Pattern,ATR,VIX,EntryHour,MarketTrend");
            }
        } catch (Exception e) {
            log.warn("Could not initialize CSV output: {}", e.getMessage());
        }

        // ============================================================
        // STEP 1: Load candle data in parallel
        // ============================================================
        int numThreads = Math.min(8, Math.max(1, config.tickers().size()));
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

        try (ExecutorService processExecutor = Executors.newFixedThreadPool(numThreads, r -> {
            Thread t = new Thread(r);
            t.setName("backtest-worker-" + t.threadId());
            t.setDaemon(true);
            return t;
        })) {
            List<CompletableFuture<Void>> processFutures = config.tickers().stream()
                .map(ticker -> CompletableFuture.runAsync(() -> {
                    try {
                        int candleCount = allData.getOrDefault(ticker, Map.of())
                                .getOrDefault(execTf, List.of()).size();
                        int idx = processedCount.incrementAndGet();
                        log.info("[{}/{}] Processing ticker: {} ({} candles)", idx, config.tickers().size(), ticker, candleCount);

                        TickerResult result = processSingleTicker(
                                ticker, config, allData, capitalPerTicker, csvLock, tradesCsvPath);

                        tickerResults.put(ticker, result);
                        log.info("[{}/{}] Completed ticker: {} - {} trades, PnL=${:.2f}",
                                idx, config.tickers().size(), ticker, result.trades.size(),
                                result.trades.stream().mapToDouble(TradeRecord::netPnl).sum());
                    } catch (Exception e) {
                        log.error("Failed to process ticker {}: {}", ticker, e.getMessage(), e);
                        tickerResults.put(ticker, new TickerResult(List.of(), List.of(), 0));
                    }
                }, processExecutor))
                .toList();
            CompletableFuture.allOf(processFutures.toArray(new CompletableFuture<?>[0])).join();
        }

        // ============================================================
        // STEP 3: Merge results into final report
        // ============================================================
        long elapsed = System.currentTimeMillis() - startTime;

        // Combine all trades
        List<TradeRecord> allTrades = tickerResults.values().stream()
                .flatMap(r -> r.trades.stream())
                .sorted(Comparator.comparing(TradeRecord::entryTime))
                .toList();

        // Build combined equity curve
        List<BacktestReport.EquityPoint> combinedEquity = tickerResults.values().stream()
                .flatMap(r -> r.equityCurve.stream())
                .sorted(Comparator.comparing(p -> p.timestamp()))
                .toList();

        // Calculate aggregate stats
        double totalPnl = allTrades.stream().mapToDouble(TradeRecord::netPnl).sum();
        double finalCapital = config.initialCapital() + totalPnl;
        int wins = (int) allTrades.stream().filter(t -> t.netPnl() > 0).count();
        int losses = (int) allTrades.stream().filter(t -> t.netPnl() <= 0).count();
        double winRate = allTrades.isEmpty() ? 0 : (double) wins / allTrades.size();

        double totalProfit = allTrades.stream().filter(t -> t.netPnl() > 0).mapToDouble(TradeRecord::netPnl).sum();
        double totalLoss = Math.abs(allTrades.stream().filter(t -> t.netPnl() < 0).mapToDouble(TradeRecord::netPnl).sum());
        double profitFactor = totalLoss == 0 ? (totalProfit > 0 ? Double.POSITIVE_INFINITY : 0) : totalProfit / totalLoss;

        double maxDrawdown = 0, maxDrawdownPct = 0, peak = config.initialCapital();
        for (BacktestReport.EquityPoint p : combinedEquity) {
            if (p.equity() > peak) peak = p.equity();
            double dd = peak - p.equity();
            if (dd > maxDrawdown) { maxDrawdown = dd; maxDrawdownPct = dd / peak; }
        }

        double avgWin = wins > 0 ? allTrades.stream().filter(t -> t.netPnl() > 0).mapToDouble(TradeRecord::netPnl).average().orElse(0) : 0;
        double avgLoss = losses > 0 ? allTrades.stream().filter(t -> t.netPnl() < 0).mapToDouble(TradeRecord::netPnl).average().orElse(0) : 0;
        double avgDuration = allTrades.stream().mapToDouble(t -> java.time.Duration.between(t.entryTime(), t.exitTime()).toMinutes() / 60.0).average().orElse(0);
        double sharpe = calculateSharpe(allTrades, config.initialCapital());

        Map<String, BacktestReport.StrategyStats> byStrategy = computePerGroupStats(allTrades, TradeRecord::strategy);
        Map<String, BacktestReport.StrategyStats> byTicker = computePerGroupStats(allTrades, TradeRecord::ticker);

        BacktestReport report = new BacktestReport(
                config.initialCapital(), finalCapital, totalPnl,
                totalPnl / config.initialCapital(),
                allTrades.size(), wins, losses, winRate, profitFactor,
                maxDrawdown, maxDrawdownPct, sharpe, avgWin, avgLoss, avgDuration,
                byStrategy, byTicker, combinedEquity, allTrades, elapsed);

        // Write summary and equity CSV
        writeSummaryReport(report);
        writeEquityCsv(combinedEquity);

        log.info("<<< Backtest complete: equity=${:.2f}, elapsed={}ms ({:.1f}s)", finalCapital, elapsed, elapsed / 1000.0);
        return report;
    }

    /**
     * Processes a single ticker's candles sequentially, maintaining its own state.
     * This method is designed to be called from parallel threads.
     */
    private TickerResult processSingleTicker(String ticker, BacktestConfig config,
            Map<String, Map<TimeFrame, List<Candle>>> allData, double initialCapital,
            ReentrantLock csvLock, Path tradesCsvPath) {

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

        for (Candle currentCandle : execCandles) {
            ZonedDateTime candleTime = currentCandle.timestamp();

            // Build StrategyData with all candles up to this point
            Map<TimeFrame, List<Candle>> dataUpToNow = buildDataUpTo(allData, ticker, candleTime);
            if (dataUpToNow.size() < 4) continue;

            StrategyData data = new StrategyData(dataUpToNow);

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
                    // Thread-safe CSV write
                    csvLock.lock();
                    try {
                        appendTradeToCsv(trade, tradesCsvPath);
                    } finally {
                        csvLock.unlock();
                    }
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
            equityCurve.add(new BacktestReport.EquityPoint(candleTime, equity));

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
            csvLock.lock();
            try {
                appendTradeToCsv(trade, tradesCsvPath);
            } finally {
                csvLock.unlock();
            }
        }
        openPositions.clear();

        return new TickerResult(trades, equityCurve, equity);
    }

    /**
     * Thread-safe CSV append for a single trade.
     */
    private void appendTradeToCsv(TradeRecord trade, Path filePath) {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath, StandardOpenOption.APPEND))) {
            pw.printf(Locale.US, "%s,%s,%s,%d,%.2f,%s,%.2f,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%.2f,%.2f,%d,%s%n",
                    trade.ticker(), trade.strategy(), trade.direction(), trade.quantity(),
                    trade.entryPrice(), trade.entryTime().format(TS_FMT),
                    trade.exitPrice(), trade.exitTime().format(TS_FMT),
                    trade.exitReason(), trade.grossPnl(), trade.commission(),
                    trade.slippage(), trade.netPnl(), trade.maxDrawdown(), trade.maxRunup(),
                    trade.candlestickPattern(), trade.atrAtEntry(), trade.vixAtEntry(),
                    trade.entryHour(), trade.marketTrend());
        } catch (Exception e) {
            log.warn("Failed to append trade CSV: {}", e.getMessage());
        }
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
     * Calculates the Sharpe ratio from trade returns.
     */
    private double calculateSharpe(List<TradeRecord> trades, double initialCapital) {
        if (trades.size() < 2) return 0;
        double[] returns = trades.stream().mapToDouble(t -> t.netPnl() / initialCapital).toArray();
        double avg = Arrays.stream(returns).average().orElse(0);
        double variance = Arrays.stream(returns).map(r -> Math.pow(r - avg, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        return stdDev == 0 ? 0 : avg / stdDev * Math.sqrt(252);
    }

    /**
     * Computes per-group statistics (by strategy or by ticker).
     */
    private Map<String, BacktestReport.StrategyStats> computePerGroupStats(
            List<TradeRecord> trades, java.util.function.Function<TradeRecord, String> grouper) {
        return trades.stream().collect(java.util.stream.Collectors.groupingBy(grouper, java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toList(), group -> {
                    int gWins = (int) group.stream().filter(t -> t.netPnl() > 0).count();
                    double gProfit = group.stream().filter(t -> t.netPnl() > 0).mapToDouble(TradeRecord::netPnl).sum();
                    double gLoss = Math.abs(group.stream().filter(t -> t.netPnl() < 0).mapToDouble(TradeRecord::netPnl).sum());
                    double gPf = gLoss == 0 ? (gProfit > 0 ? Double.POSITIVE_INFINITY : 0) : gProfit / gLoss;
                    return new BacktestReport.StrategyStats(group.size(), gWins,
                            group.isEmpty() ? 0 : (double) gWins / group.size(),
                            group.stream().mapToDouble(TradeRecord::netPnl).sum(), gPf,
                            group.stream().mapToDouble(TradeRecord::maxDrawdown).max().orElse(0));
                })));
    }

    private Map<TimeFrame, List<Candle>> buildDataUpTo(
            Map<String, Map<TimeFrame, List<Candle>>> allData, String ticker, ZonedDateTime candleTime) {
        Map<TimeFrame, List<Candle>> result = new EnumMap<>(TimeFrame.class);
        Map<TimeFrame, List<Candle>> tickerData = allData.get(ticker);
        if (tickerData == null) return result;

        for (Map.Entry<TimeFrame, List<Candle>> entry : tickerData.entrySet()) {
            List<Candle> upToNow = entry.getValue().stream()
                    .filter(c -> !c.timestamp().isAfter(candleTime))
                    .toList();
            if (!upToNow.isEmpty()) {
                result.put(entry.getKey(), upToNow);
            }
        }
        return result;
    }

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
