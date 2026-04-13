package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport.EquityPoint;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport.StrategyStats;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.service.TickerService;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Web UI Dashboard for backtest analysis and strategy improvement.
 * Access at: http://localhost:9090/backtest-ui
 * 
 * Features:
 * 1. Interactive equity curve chart (Chart.js)
 * 2. Strategy performance table with "Improve" buttons
 * 3. One-click analysis with auto-recommendations
 * 4. Before/after comparison when retesting with new params
 */
@Slf4j
@RestController
@RequestMapping("/backtest-ui")
@RequiredArgsConstructor
public class BacktestDashboardController {

    private final BacktestEngine backtestEngine;
    private final TickerService tickerService;
    private final TickerMemory tickerMemory;

    /**
     * Serves the main dashboard HTML page.
     * GET /backtest-ui
     */
    @GetMapping
    public String dashboard() {
        return buildDashboardHtml();
    }

    /**
     * SSE endpoint for streaming backtest progress in real-time.
     * GET /backtest-ui/stream
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBacktest(
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct
    ) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        AtomicBoolean running = new AtomicBoolean(false);

        // Get tickers: hot first, then rest
        List<String> hotTickers = tickerService.getHotTickers();
        List<String> allTickers = tickerService.getTickerSymbols();
        List<String> tickers = new ArrayList<>(hotTickers);
        allTickers.stream()
                .filter(t -> !hotTickers.contains(t))
                .forEach(tickers::add);

        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusYears(1);

        log.info("Starting streaming backtest: {} tickers, {} to {}", tickers.size(), fromDate, toDate);

        // Send start event
        try {
            Map<String, Object> startEvent = new LinkedHashMap<>();
            startEvent.put("type", "start");
            startEvent.put("tickerCount", tickers.size());
            startEvent.put("hotTickers", hotTickers.size());
            startEvent.put("totalTickers", allTickers.size());
            startEvent.put("dateRange", fromDate + " to " + toDate);
            startEvent.put("capital", initialCapital);
            emitter.send(SseEmitter.event().name("start").data(startEvent));
            log.info("SSE start event sent");
        } catch (IOException e) {
            log.error("Failed to send SSE start event: {}", e.getMessage());
            emitter.completeWithError(e);
            return emitter;
        }

        // Run backtest in background thread with parallel processing
        CompletableFuture.runAsync(() -> {
            try {
                running.set(true);

                // Ensure backtest directory exists
                Path tradesCsv = Path.of("backtest/trades.csv");
                if (!Files.exists(tradesCsv.getParent())) {
                    Files.createDirectories(tradesCsv.getParent());
                }

                long lastFileLength = 0;
                int lastTradeCount = 0;
                long startTime = System.currentTimeMillis();
                long lastLogTime = startTime;

                // Use a thread pool for parallel CSV reading and event sending
                java.util.concurrent.ExecutorService eventExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);

                // Start the backtest
                BacktestConfig config = new BacktestConfig(
                        tickers, fromDate, toDate,
                        initialCapital, riskPct, 0.005, 0.65,
                        3, com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15, true
                );

                final BacktestReport[] finalReport = new BacktestReport[1];
                final Exception[] backtestError = new Exception[1];
                
                Thread backtestThread = new Thread(() -> {
                    try {
                        log.info("Starting backtest engine...");
                        finalReport[0] = backtestEngine.run(config);
                        log.info("Backtest engine completed");
                    } catch (Exception e) {
                        log.error("Backtest engine failed: {}", e.getMessage(), e);
                        backtestError[0] = e;
                    }
                });
                backtestThread.setDaemon(true);
                backtestThread.start();

                // Monitor progress while backtest runs
                while (backtestThread.isAlive()) {
                    Thread.sleep(1000); // Check every 1 second

                    // Log progress every 10 seconds AND send to UI
                    long now = System.currentTimeMillis();
                    if (now - lastLogTime > 10000) {
                        int currentTradeCount = countTradesInCsv(tradesCsv);
                        long elapsedSec = (now - startTime) / 1000;
                        log.info("Backtest progress: {}s elapsed, {} trades so far",
                                elapsedSec, currentTradeCount);
                        lastLogTime = now;

                        // Send progress update to UI
                        final long finalElapsedSec = elapsedSec;
                        final int finalTradeCount = currentTradeCount;
                        eventExecutor.submit(() -> {
                            try {
                                Map<String, Object> progressUpdate = new LinkedHashMap<>();
                                progressUpdate.put("type", "progress_update");
                                progressUpdate.put("elapsedSec", finalElapsedSec);
                                progressUpdate.put("totalTrades", finalTradeCount);
                                emitter.send(SseEmitter.event().data(progressUpdate));
                            } catch (IOException e) {
                                log.warn("Error sending progress update: {}", e.getMessage());
                            }
                        });
                    }

                    // Read current trades from CSV (batch updates to avoid flooding UI)
                    int currentTradeCount = countTradesInCsv(tradesCsv);
                    if (currentTradeCount > lastTradeCount) {
                        final int tradesToSendFrom = lastTradeCount;
                        final int newTradeCount = currentTradeCount;
                        final long currentElapsed = System.currentTimeMillis() - startTime;

                        eventExecutor.submit(() -> {
                            try {
                                List<Map<String, Object>> newTrades = readNewTradesFromCsv(tradesCsv, tradesToSendFrom);

                                if (!newTrades.isEmpty()) {
                                    long wins = newTrades.stream().filter(t -> (double) t.getOrDefault("netPnl", 0.0) > 0).count();
                                    double totalPnl = newTrades.stream().mapToDouble(t -> (double) t.getOrDefault("netPnl", 0.0)).sum();

                                    Map<String, Object> progressEvent = new LinkedHashMap<>();
                                    progressEvent.put("type", "trades");
                                    progressEvent.put("trades", newTrades);
                                    progressEvent.put("totalTrades", newTradeCount);
                                    progressEvent.put("elapsedMs", currentElapsed);
                                    progressEvent.put("recentWins", wins);
                                    progressEvent.put("recentPnl", totalPnl);

                                    emitter.send(SseEmitter.event().data(progressEvent));
                                    log.debug("SSE progress event sent: {} new trades, totalPnl={}", newTrades.size(), totalPnl);
                                }
                            } catch (IOException e) {
                                log.warn("Error sending SSE progress event: {}", e.getMessage());
                            } catch (Exception e) {
                                log.error("Unexpected error in SSE progress: {}", e.getMessage(), e);
                            }
                        });

                        lastTradeCount = currentTradeCount;
                    }

                    // Also check equity curve
                    Path equityCsv = Path.of("backtest/equity.csv");
                    if (Files.exists(equityCsv)) {
                        long currentLength = Files.size(equityCsv);
                        if (currentLength > lastFileLength) {
                            final long finalLength = currentLength;
                            eventExecutor.submit(() -> {
                                try {
                                    List<String> lines = Files.readAllLines(equityCsv);
                                    if (!lines.isEmpty()) {
                                        String lastLine = lines.get(lines.size() - 1);
                                        String[] parts = lastLine.split(",");
                                        if (parts.length >= 2) {
                                            Map<String, Object> equityEvent = new LinkedHashMap<>();
                                            equityEvent.put("type", "equity");
                                            equityEvent.put("time", parts[0].trim());
                                            equityEvent.put("equity", Double.parseDouble(parts[1].trim()));
                                            emitter.send(SseEmitter.event().data(equityEvent));
                                        }
                                    }
                                } catch (Exception e) {
                                    // Ignore equity read errors
                                }
                            });
                            lastFileLength = currentLength;
                        }
                    }
                }
                
                // Wait for backtest thread to fully complete
                backtestThread.join(2000);
                eventExecutor.shutdown();
                eventExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS);
                
                log.info("Backtest thread finished, checking results...");

                // Check for errors
                if (backtestError[0] != null) {
                    throw backtestError[0];
                }

                // Send final results
                if (finalReport[0] != null) {
                    BacktestReport report = finalReport[0];
                    java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");

                    List<Map<String, Object>> equityData = new ArrayList<>();
                    for (EquityPoint point : report.equityCurve()) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("time", point.timestamp().format(timeFmt));
                        entry.put("equity", point.equity());
                        equityData.add(entry);
                    }

                    Map<String, Object> byStrategy = new LinkedHashMap<>();
                    for (Map.Entry<String, StrategyStats> entry : report.byStrategy().entrySet()) {
                        Map<String, Object> stats = new LinkedHashMap<>();
                        stats.put("trades", entry.getValue().trades());
                        stats.put("winRate", entry.getValue().winRate());
                        stats.put("totalPnl", entry.getValue().totalPnl());
                        stats.put("profitFactor", entry.getValue().profitFactor());
                        stats.put("maxDrawdown", entry.getValue().maxDrawdown());
                        byStrategy.put(entry.getKey(), stats);
                    }

                    Map<String, Object> byTicker = new LinkedHashMap<>();
                    for (Map.Entry<String, StrategyStats> entry : report.byTicker().entrySet()) {
                        Map<String, Object> stats = new LinkedHashMap<>();
                        stats.put("trades", entry.getValue().trades());
                        stats.put("winRate", entry.getValue().winRate());
                        stats.put("totalPnl", entry.getValue().totalPnl());
                        stats.put("profitFactor", entry.getValue().profitFactor());
                        byTicker.put(entry.getKey(), stats);
                    }

                    Map<String, Object> completeEvent = new LinkedHashMap<>();
                    completeEvent.put("type", "complete");
                    completeEvent.put("totalTrades", report.totalTrades());
                    completeEvent.put("winRate", report.winRate() * 100);
                    completeEvent.put("totalPnl", report.finalCapital() - report.initialCapital());
                    completeEvent.put("profitFactor", report.profitFactor());
                    completeEvent.put("maxDrawdown", report.maxDrawdown());
                    completeEvent.put("equityCurve", equityData);
                    completeEvent.put("byStrategy", byStrategy);
                    completeEvent.put("byTicker", byTicker);
                    completeEvent.put("elapsedMs", System.currentTimeMillis() - startTime);

                    emitter.send(SseEmitter.event().name("complete").data(completeEvent));
                    log.info("SSE complete event sent: {} trades", report.totalTrades());
                } else {
                    log.warn("Backtest report is null after completion");
                }

                emitter.complete();
                log.info("SSE emitter completed");
            } catch (Exception e) {
                log.error("Streaming backtest failed: {}", e.getMessage(), e);
                try {
                    Map<String, Object> errorEvent = new LinkedHashMap<>();
                    errorEvent.put("type", "error");
                    errorEvent.put("error", e.getMessage());
                    emitter.send(SseEmitter.event().name("error").data(errorEvent));
                    emitter.complete();
                } catch (IOException ex) {
                    log.error("Failed to send error event: {}", ex.getMessage());
                    emitter.completeWithError(ex);
                }
            } finally {
                running.set(false);
            }
        });

        // Handle client disconnect
        emitter.onTimeout(() -> {
            log.info("SSE client disconnected (timeout)");
            running.set(false);
            emitter.complete();
        });
        emitter.onCompletion(() -> {
            log.info("SSE connection completed");
            running.set(false);
        });
        emitter.onError((e) -> {
            log.error("SSE error: {}", e.getMessage());
            running.set(false);
        });

        return emitter;
    }

    /**
     * Counts the number of trades in the CSV file (excluding header).
     */
    private int countTradesInCsv(Path csvPath) {
        try {
            if (!Files.exists(csvPath)) return 0;
            List<String> lines = Files.readAllLines(csvPath);
            return Math.max(0, lines.size() - 1); // Exclude header
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Reads new trades from CSV starting from a given index.
     */
    private List<Map<String, Object>> readNewTradesFromCsv(Path csvPath, int startIndex) {
        List<Map<String, Object>> trades = new ArrayList<>();
        try {
            if (!Files.exists(csvPath)) return trades;
            List<String> lines = Files.readAllLines(csvPath);

            // Start from startIndex + 1 (skip header)
            int startIdx = startIndex + 1;
            for (int i = startIdx; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length >= 20) {
                    Map<String, Object> trade = new LinkedHashMap<>();
                    trade.put("ticker", parts[0].trim());
                    trade.put("strategy", parts[1].trim());
                    trade.put("direction", parts[2].trim());
                    trade.put("netPnl", Double.parseDouble(parts[12].trim()));
                    trade.put("exitReason", parts[8].trim());
                    trade.put("pattern", parts[15].trim());
                    trade.put("entryTime", parts[5].trim());
                    
                    // Build chart file path (using /charts/ endpoint)
                    String entryTime = parts[5].trim();
                    String chartFilename = String.format("%s_%s_%s_%s.html",
                            parts[0].trim(),
                            parts[1].trim(),
                            parts[2].trim(),
                            entryTime.replace(" ", "_").replace(":", "-"));
                    trade.put("chartPath", chartFilename); // Just the filename, JS will prefix /charts/
                    
                    trades.add(trade);
                }
            }
        } catch (Exception e) {
            log.warn("Error reading new trades from CSV: {}", e.getMessage());
        }
        return trades;
    }

    /**
     * API: Run a backtest with default parameters (hot tickers focus).
     * POST /backtest-ui/run
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct
    ) {
        try {
            // Get tickers: hot first, then rest
            List<String> hotTickers = tickerService.getHotTickers();
            List<String> allTickers = tickerService.getTickerSymbols();
            List<String> tickers = new ArrayList<>(hotTickers);
            allTickers.stream()
                    .filter(t -> !hotTickers.contains(t))
                    .forEach(tickers::add);

            LocalDate toDate = LocalDate.now();
            LocalDate fromDate = toDate.minusYears(1);

            log.info("Running backtest via web UI: {} tickers ({} hot + {} rest), {} to {}",
                    tickers.size(), hotTickers.size(), allTickers.size() - hotTickers.size(), fromDate, toDate);

            BacktestConfig config = new BacktestConfig(
                    tickers, fromDate, toDate,
                    initialCapital, riskPct, 0.005, 0.65,
                    3, com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15, true
            );

            BacktestReport report = backtestEngine.run(config);

            // Build equity curve data
            List<Map<String, Object>> equityData = new ArrayList<>();
            java.time.format.DateTimeFormatter timeFmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
            for (EquityPoint point : report.equityCurve()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("time", point.timestamp().format(timeFmt));
                entry.put("equity", point.equity());
                equityData.add(entry);
            }

            // Build strategy data
            Map<String, Object> byStrategy = new LinkedHashMap<>();
            for (Map.Entry<String, StrategyStats> entry : report.byStrategy().entrySet()) {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("trades", entry.getValue().trades());
                stats.put("winRate", entry.getValue().winRate());
                stats.put("totalPnl", entry.getValue().totalPnl());
                stats.put("profitFactor", entry.getValue().profitFactor());
                stats.put("maxDrawdown", entry.getValue().maxDrawdown());
                byStrategy.put(entry.getKey(), stats);
            }

            // Build ticker data
            Map<String, Object> byTicker = new LinkedHashMap<>();
            for (Map.Entry<String, StrategyStats> entry : report.byTicker().entrySet()) {
                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("trades", entry.getValue().trades());
                stats.put("winRate", entry.getValue().winRate());
                stats.put("totalPnl", entry.getValue().totalPnl());
                stats.put("profitFactor", entry.getValue().profitFactor());
                byTicker.put(entry.getKey(), stats);
            }

            // Build response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("totalTrades", report.totalTrades());
            result.put("winRate", report.winRate() * 100);
            result.put("totalPnl", report.finalCapital() - report.initialCapital());
            result.put("profitFactor", report.profitFactor());
            result.put("maxDrawdown", report.maxDrawdown());
            result.put("equityCurve", equityData);
            result.put("byStrategy", byStrategy);
            result.put("byTicker", byTicker);
            result.put("tickerCount", tickers.size());
            result.put("hotTickers", hotTickers.size());

            log.info("Backtest complete via UI: {} trades, {:.1f}% WR, ${:.2f} PnL",
                    report.totalTrades(), report.winRate() * 100, report.finalCapital() - report.initialCapital());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Backtest failed via UI: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * API: Analyze a specific underperforming strategy and suggest improvements.
     * POST /backtest-ui/improve/{strategyName}
     */
    @PostMapping("/improve/{strategyName}")
    public ResponseEntity<Map<String, Object>> improveStrategy(
            @PathVariable String strategyName
    ) {
        try {
            Map<String, Object> analysis = new LinkedHashMap<>();

            // Read trades and find all trades for this strategy
            List<Map<String, Object>> strategyTrades = readStrategyTrades(strategyName);

            if (strategyTrades.isEmpty()) {
                analysis.put("success", false);
                analysis.put("message", "No trades found for strategy: " + strategyName);
                return ResponseEntity.ok(analysis);
            }

            // Calculate stats
            long wins = strategyTrades.stream().filter(t -> (double) t.getOrDefault("netPnl", 0.0) > 0).count();
            long losses = strategyTrades.size() - wins;
            double totalPnl = strategyTrades.stream().mapToDouble(t -> (double) t.getOrDefault("netPnl", 0.0)).sum();
            double winRate = (double) wins / strategyTrades.size();
            double avgWin = wins > 0 ? strategyTrades.stream().filter(t -> (double) t.get("netPnl") > 0)
                    .mapToDouble(t -> (double) t.get("netPnl")).average().orElse(0) : 0;
            double avgLoss = losses > 0 ? strategyTrades.stream().filter(t -> (double) t.get("netPnl") <= 0)
                    .mapToDouble(t -> Math.abs((double) t.get("netPnl"))).average().orElse(0) : 0;

            // Analyze exit reasons
            Map<String, Long> exitReasons = new LinkedHashMap<>();
            for (Map<String, Object> trade : strategyTrades) {
                String reason = (String) trade.getOrDefault("exitReason", "unknown");
                exitReasons.merge(reason, 1L, Long::sum);
            }

            // Generate recommendations
            List<String> recommendations = new ArrayList<>();
            List<String> suggestedParams = new ArrayList<>();

            if (winRate < 0.40 && strategyTrades.size() >= 3) {
                recommendations.add("Win rate is low (" + String.format("%.1f%%", winRate * 100) + ") — consider widening SL ATR multiplier by 0.3");
                suggestedParams.add("SL ATR: increase by 0.3 (e.g., 2.0 → 2.3)");
                recommendations.add("Review entry conditions: may be entering too early/late");
                recommendations.add("Consider disabling this strategy for current market conditions");
            }
            if (totalPnl < -500 && strategyTrades.size() >= 3) {
                recommendations.add("Strategy is losing money — reduce position size by 50%");
                suggestedParams.add("Position size: reduce by 50%");
                recommendations.add("Check if market regime has changed (trending vs ranging)");
            }
            if (avgLoss > avgWin * 1.5 && losses > 2) {
                recommendations.add("Average loss is " + String.format("%.0f%%", (avgLoss / avgWin - 1) * 100) + " larger than average win — tighten SL or reduce risk");
                suggestedParams.add("Risk per trade: reduce from 2% to 1%");
            }
            if (winRate >= 0.60 && totalPnl > 0 && strategyTrades.size() >= 3) {
                recommendations.add("Strategy is performing well — consider increasing position size");
                suggestedParams.add("Position size: increase by 25%");
                recommendations.add("This strategy is a winner — allocate more capital");
            }

            // Exit reason analysis
            long slHits = exitReasons.getOrDefault("SL", 0L);
            long tpHits = exitReasons.getOrDefault("TP", 0L);
            if (slHits > tpHits && strategyTrades.size() >= 3) {
                recommendations.add("More SL hits (" + slHits + ") than TP hits (" + tpHits + ") — SL might be too tight");
                suggestedParams.add("SL ATR: widen by 0.2-0.5");
            }

            analysis.put("success", true);
            analysis.put("strategy", strategyName);
            analysis.put("totalTrades", strategyTrades.size());
            analysis.put("wins", wins);
            analysis.put("losses", losses);
            analysis.put("winRate", winRate * 100);
            analysis.put("totalPnl", totalPnl);
            analysis.put("avgWin", avgWin);
            analysis.put("avgLoss", avgLoss);
            analysis.put("exitReasons", exitReasons);
            analysis.put("recommendations", recommendations);
            analysis.put("suggestedParams", suggestedParams);

            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            log.error("Error analyzing strategy {}: {}", strategyName, e.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * API: Retest with improved parameters (simulated).
     * POST /backtest-ui/retest/{strategyName}
     * 
     * This runs a new backtest and compares the results with the previous one.
     * In a full implementation, this would temporarily adjust RiskCalculator parameters.
     */
    @PostMapping("/retest/{strategyName}")
    public ResponseEntity<Map<String, Object>> retestStrategy(
            @PathVariable String strategyName,
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct
    ) {
        try {
            log.info("Retesting strategy: {} with capital=${}, risk={}%", strategyName, initialCapital, riskPct * 100);

            // Get tickers: hot first, then rest
            List<String> hotTickers = tickerService.getHotTickers();
            List<String> allTickers = tickerService.getTickerSymbols();
            List<String> tickers = new ArrayList<>(hotTickers);
            allTickers.stream()
                    .filter(t -> !hotTickers.contains(t))
                    .forEach(tickers::add);

            LocalDate toDate = LocalDate.now();
            LocalDate fromDate = toDate.minusYears(1);

            BacktestConfig config = new BacktestConfig(
                    tickers, fromDate, toDate,
                    initialCapital, riskPct, 0.005, 0.65,
                    3, com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15, true
            );

            BacktestReport report = backtestEngine.run(config);

            // Read previous strategy trades for comparison
            List<Map<String, Object>> previousTrades = readStrategyTrades(strategyName);
            double previousPnl = previousTrades.stream().mapToDouble(t -> (double) t.getOrDefault("netPnl", 0.0)).sum();
            long previousWins = previousTrades.stream().filter(t -> (double) t.getOrDefault("netPnl", 0.0) > 0).count();
            double previousWinRate = previousTrades.isEmpty() ? 0 : (double) previousWins / previousTrades.size();

            // Find current strategy stats from report
            StrategyStats currentStats = report.byStrategy().get(strategyName);
            double currentPnl = currentStats != null ? currentStats.totalPnl() : 0;
            double currentWinRate = currentStats != null ? currentStats.winRate() : 0;
            int currentTrades = currentStats != null ? currentStats.trades() : 0;

            // Build comparison
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("strategy", strategyName);

            // Previous (from CSV)
            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put("trades", previousTrades.size());
            previous.put("winRate", previousWinRate * 100);
            previous.put("totalPnl", previousPnl);

            // Current (from new backtest)
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("trades", currentTrades);
            current.put("winRate", currentWinRate * 100);
            current.put("totalPnl", currentPnl);

            // Improvement
            Map<String, Object> improvement = new LinkedHashMap<>();
            improvement.put("pnlDiff", currentPnl - previousPnl);
            improvement.put("winRateDiff", (currentWinRate - previousWinRate) * 100);
            improvement.put("improved", currentPnl > previousPnl);

            result.put("previous", previous);
            result.put("current", current);
            result.put("improvement", improvement);

            log.info("Retest complete for {}: Before PnL=${:.2f}, After PnL=${:.2f}, Diff=${:.2f}",
                    strategyName, previousPnl, currentPnl, currentPnl - previousPnl);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Retest failed for {}: {}", strategyName, e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * Reads trades for a specific strategy from trades.csv.
     */
    private List<Map<String, Object>> readStrategyTrades(String strategyName) {
        List<Map<String, Object>> trades = new ArrayList<>();
        Path tradesCsv = Path.of("backtest/trades.csv");
        if (!Files.exists(tradesCsv)) return trades;

        try {
            List<String> lines = Files.readAllLines(tradesCsv);
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                if (parts.length >= 20 && parts[1].trim().equals(strategyName)) {
                    Map<String, Object> trade = new LinkedHashMap<>();
                    trade.put("ticker", parts[0].trim());
                    trade.put("strategy", parts[1].trim());
                    trade.put("direction", parts[2].trim());
                    trade.put("entryPrice", Double.parseDouble(parts[4].trim()));
                    trade.put("entryTime", parts[5].trim());
                    trade.put("exitPrice", Double.parseDouble(parts[6].trim()));
                    trade.put("exitTime", parts[7].trim());
                    trade.put("exitReason", parts[8].trim());
                    trade.put("netPnl", Double.parseDouble(parts[12].trim()));
                    trade.put("pattern", parts[15].trim());
                    trades.add(trade);
                }
            }
        } catch (Exception e) {
            log.warn("Error reading strategy trades for {}: {}", strategyName, e.getMessage());
        }

        return trades;
    }

    /**
     * Builds the complete HTML dashboard with Chart.js and dark theme.
     */
    private String buildDashboardHtml() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Options Quant Backtest Dashboard</title>
                    <script src="https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js"></script>
                    <style>
                        * { margin: 0; padding: 0; box-sizing: border-box; }
                        body { background: #0d1117; color: #c9d1d9; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
                        .container { max-width: 1400px; margin: 0 auto; padding: 20px; }
                        header { text-align: center; padding: 20px 0; border-bottom: 1px solid #21262d; margin-bottom: 20px; }
                        h1 { color: #58a6ff; font-size: 28px; margin-bottom: 5px; }
                        h2 { color: #58a6ff; font-size: 20px; margin: 20px 0 10px; padding-bottom: 5px; border-bottom: 1px solid #21262d; }
                        h3 { color: #58a6ff; font-size: 16px; margin: 15px 0 10px; }
                        h4 { color: #c9d1d9; font-size: 14px; margin: 10px 0 5px; }
                        .card { background: #161b22; border: 1px solid #21262d; border-radius: 8px; padding: 20px; margin-bottom: 20px; }
                        .btn { padding: 10px 20px; border: none; border-radius: 6px; cursor: pointer; font-size: 14px; font-weight: 500; margin-right: 10px; }
                        .btn-primary { background: #238636; color: white; }
                        .btn-primary:hover { background: #2ea043; }
                        .btn-warning { background: #9e6a03; color: white; }
                        .btn-warning:hover { background: #bb8009; }
                        .btn-secondary { background: #21262d; color: #c9d1d9; }
                        .btn-secondary:hover { background: #30363d; }
                        .btn:disabled { opacity: 0.5; cursor: not-allowed; }
                        .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 15px; margin: 15px 0; }
                        .stat { background: #0d1117; padding: 15px; border-radius: 6px; text-align: center; }
                        .stat-value { font-size: 24px; font-weight: bold; }
                        .stat-label { font-size: 12px; color: #8b949e; margin-top: 5px; }
                        .positive { color: #3fb950; }
                        .negative { color: #f85149; }
                        table { width: 100%; border-collapse: collapse; margin-top: 10px; }
                        th { background: #0d1117; padding: 10px; text-align: left; border-bottom: 2px solid #21262d; font-size: 12px; color: #8b949e; }
                        td { padding: 10px; border-bottom: 1px solid #21262d; font-size: 13px; }
                        tr:hover { background: #1c2128; }
                        .loading { display: none; text-align: center; padding: 20px; }
                        .loading.active { display: block; }
                        .spinner { border: 3px solid #21262d; border-top: 3px solid #58a6ff; border-radius: 50%; width: 30px; height: 30px; animation: spin 1s linear infinite; margin: 0 auto; }
                        @keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
                        #chart-container { height: 400px; margin: 15px 0; }
                        .improve-modal { display: none; position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.7); z-index: 1000; }
                        .improve-modal.active { display: flex; justify-content: center; align-items: center; }
                        .improve-content { background: #161b22; border: 1px solid #21262d; border-radius: 8px; padding: 20px; max-width: 700px; width: 90%; max-height: 80vh; overflow-y: auto; }
                        .improve-content ul { list-style: none; }
                        .improve-content li { padding: 8px 0; border-bottom: 1px solid #21262d; font-size: 13px; }
                        .improve-content li:before { content: "→ "; color: #58a6ff; }
                        .comparison-table { width: 100%; margin: 15px 0; }
                        .comparison-table td { padding: 8px; }
                        .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
                        .badge-success { background: #238636; color: white; }
                        .badge-danger { background: #da3633; color: white; }
                        .badge-warning { background: #9e6a03; color: white; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <header>
                            <h1>🧠 Options Quant Backtest Dashboard</h1>
                            <p style="color: #8b949e; margin-top: 5px;">Run backtests, analyze equity curves, and improve strategies with one click</p>
                        </header>

                        <!-- Controls -->
                        <div class="card">
                            <h2>🚀 Run Backtest</h2>
                            <p style="color: #8b949e; margin-bottom: 15px;">
                                Default: Hot tickers first, 1 year history, $50k capital, 2% risk
                            </p>
                            <button class="btn btn-primary" onclick="runBacktest()" id="run-btn">Run Backtest</button>
                            
                            <!-- Progress Bar -->
                            <div id="progress-section" style="display: none; margin-top: 15px;">
                                <div style="background: #21262d; border-radius: 4px; height: 8px; overflow: hidden;">
                                    <div id="progress-bar" style="background: #58a6ff; height: 100%; width: 0%; transition: width 0.3s;"></div>
                                </div>
                                <p id="progress-text" style="color: #8b949e; font-size: 12px; margin-top: 5px;">Initializing...</p>
                            </div>
                        </div>

                        <!-- Live Trade Log -->
                        <div class="card" id="live-trade-log" style="display: none;">
                            <h2>📡 Trades en Vivo</h2>
                            <div style="max-height: 300px; overflow-y: auto; background: #0d1117; border-radius: 6px; padding: 10px;">
                                <table style="width: 100%;">
                                    <thead>
                                        <tr>
                                            <th>Hora</th>
                                            <th>Ticker</th>
                                            <th>Estrategia</th>
                                            <th>Dir</th>
                                            <th>Patrón</th>
                                            <th>PnL</th>
                                            <th>Salida</th>
                                            <th>Chart</th>
                                        </tr>
                                    </thead>
                                    <tbody id="live-trades-body"></tbody>
                                </table>
                            </div>
                        </div>

                        <!-- Console Log -->
                        <div class="card" id="console-log" style="display: none;">
                            <h2>💻 Consola de Progreso</h2>
                            <div id="console-output" style="max-height: 200px; overflow-y: auto; background: #0d1117; border-radius: 6px; padding: 10px; font-family: 'Courier New', monospace; font-size: 12px; color: #c9d1d9;">
                            </div>
                        </div>

                        <!-- Results -->
                        <div class="card" id="results" style="display: none;">
                            <h2>📊 Resultados</h2>
                            <div class="stats">
                                <div class="stat">
                                    <div class="stat-value" id="total-trades">-</div>
                                    <div class="stat-label">Total Trades</div>
                                </div>
                                <div class="stat">
                                    <div class="stat-value" id="win-rate">-</div>
                                    <div class="stat-label">Win Rate</div>
                                </div>
                                <div class="stat">
                                    <div class="stat-value" id="total-pnl">-</div>
                                    <div class="stat-label">Total PnL</div>
                                </div>
                                <div class="stat">
                                    <div class="stat-value" id="profit-factor">-</div>
                                    <div class="stat-label">Profit Factor</div>
                                </div>
                                <div class="stat">
                                    <div class="stat-value" id="max-dd">-</div>
                                    <div class="stat-label">Max Drawdown</div>
                                </div>
                            </div>
                        </div>

                        <!-- Equity Chart -->
                        <div class="card" id="chart-card" style="display: none;">
                            <h2>📈 Curva de Equity</h2>
                            <div id="chart-container">
                                <canvas id="equity-chart"></canvas>
                            </div>
                        </div>

                        <!-- Strategy Table -->
                        <div class="card" id="strategy-card" style="display: none;">
                            <h2>🎯 Performance por Estrategia</h2>
                            <table>
                                <thead>
                                    <tr>
                                        <th>Estrategia</th>
                                        <th>Trades</th>
                                        <th>Win Rate</th>
                                        <th>Total PnL</th>
                                        <th>Profit Factor</th>
                                        <th>Max DD</th>
                                        <th>Acciones</th>
                                    </tr>
                                </thead>
                                <tbody id="strategy-table"></tbody>
                            </table>
                        </div>

                        <!-- Ticker Table -->
                        <div class="card" id="ticker-card" style="display: none;">
                            <h2>📋 Performance por Ticker</h2>
                            <table>
                                <thead>
                                    <tr>
                                        <th>Ticker</th>
                                        <th>Trades</th>
                                        <th>Win Rate</th>
                                        <th>Total PnL</th>
                                        <th>Profit Factor</th>
                                    </tr>
                                </thead>
                                <tbody id="ticker-table"></tbody>
                            </table>
                        </div>
                    </div>

                    <!-- Fixed Footer with Running PnL -->
                    <div id="running-pnl-footer" style="display: none; position: fixed; bottom: 0; left: 0; right: 0; background: #161b22; border-top: 2px solid #21262d; padding: 12px 20px; z-index: 999; box-shadow: 0 -4px 12px rgba(0,0,0,0.5);">
                        <div style="max-width: 1400px; margin: 0 auto; display: flex; justify-content: space-between; align-items: center;">
                            <div style="display: flex; gap: 20px; align-items: center;">
                                <span style="font-size: 13px; color: #8b949e;" id="footer-trades">0 trades</span>
                                <span style="font-size: 13px; color: #8b949e;" id="footer-status">⏳ Esperando...</span>
                            </div>
                            <div style="display: flex; gap: 30px; align-items: center;">
                                <div style="text-align: center;">
                                    <div style="font-size: 11px; color: #8b949e;">Tiempo</div>
                                    <div id="footer-time" style="font-size: 16px; font-weight: bold;">0s</div>
                                </div>
                                <div style="text-align: center;">
                                    <div style="font-size: 11px; color: #8b949e;">💰 PnL Total</div>
                                    <div id="footer-pnl" style="font-size: 20px; font-weight: bold;">$0.00</div>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Improve Modal -->
                    <div class="improve-modal" id="improve-modal">
                        <div class="improve-content">
                            <h3>🔧 Improve Strategy: <span id="improve-strategy-name"></span></h3>
                            <div id="improve-loading" class="loading active">
                                <div class="spinner"></div>
                                <p style="margin-top: 10px;">Analyzing strategy...</p>
                            </div>
                            <div id="improve-results" style="display: none;">
                                <h4>Performance Analysis</h4>
                                <div id="improve-stats" class="stats" style="margin: 15px 0;"></div>
                                
                                <h4>Exit Reason Breakdown</h4>
                                <div id="improve-exit-reasons" style="margin: 10px 0; font-size: 13px;"></div>
                                
                                <h4>Recommendations</h4>
                                <ul id="improve-recommendations"></ul>
                                
                                <h4>Suggested Parameter Changes</h4>
                                <ul id="improve-params"></ul>
                                
                                <div style="margin-top: 15px;">
                                    <button class="btn btn-warning" onclick="retestStrategy()" id="retest-btn">🔄 Retest & Compare</button>
                                    <button class="btn btn-secondary" onclick="closeModal()">Close</button>
                                </div>
                                
                                <!-- Comparison Results -->
                                <div id="comparison-section" style="display: none; margin-top: 20px; border-top: 1px solid #21262d; padding-top: 15px;">
                                    <h4>📊 Before vs After Comparison</h4>
                                    <table class="comparison-table">
                                        <tr><th>Metric</th><th>Before (CSV)</th><th>After (Retest)</th><th>Diff</th></tr>
                                        <tr><td>Trades</td><td id="comp-prev-trades">-</td><td id="comp-curr-trades">-</td><td id="comp-trades-diff">-</td></tr>
                                        <tr><td>Win Rate</td><td id="comp-prev-wr">-</td><td id="comp-curr-wr">-</td><td id="comp-wr-diff">-</td></tr>
                                        <tr><td>Total PnL</td><td id="comp-prev-pnl">-</td><td id="comp-curr-pnl">-</td><td id="comp-pnl-diff">-</td></tr>
                                    </table>
                                </div>
                            </div>
                        </div>
                    </div>

                    <script>
                        let equityChart = null;
                        let currentStrategy = '';

                        async function runBacktest() {
                            const btn = document.getElementById('run-btn');
                            const progressSection = document.getElementById('progress-section');
                            const progressBar = document.getElementById('progress-bar');
                            const progressText = document.getElementById('progress-text');
                            const liveTradeLog = document.getElementById('live-trade-log');
                            const liveTradesBody = document.getElementById('live-trades-body');
                            const consoleLog = document.getElementById('console-log');
                            const consoleOutput = document.getElementById('console-output');
                            const runningPnlFooter = document.getElementById('running-pnl-footer');
                            const footerPnl = document.getElementById('footer-pnl');
                            const footerTrades = document.getElementById('footer-trades');
                            const footerTime = document.getElementById('footer-time');
                            const footerStatus = document.getElementById('footer-status');

                            // Helper: add log to console (limit to 100 entries to prevent UI hanging)
                            function addConsoleLog(message, type = 'info') {
                                const now = new Date();
                                const timestamp = now.toLocaleTimeString('es-AR', { hour12: false });
                                const colors = { info: '#58a6ff', success: '#3fb950', warning: '#d29922', error: '#f85149' };
                                const color = colors[type] || colors.info;
                                const line = document.createElement('div');
                                line.style.color = color;
                                line.style.marginBottom = '3px';
                                line.innerHTML = `<span style="color: #8b949e;">[${timestamp}]</span> ${message}`;
                                consoleOutput.appendChild(line);
                                
                                // Limit to 100 entries to prevent UI hanging
                                while (consoleOutput.children.length > 100) {
                                    consoleOutput.removeChild(consoleOutput.firstChild);
                                }
                                
                                consoleOutput.scrollTop = consoleOutput.scrollHeight;
                            }

                            // Reset UI
                            btn.disabled = true;
                            progressSection.style.display = 'block';
                            progressBar.style.width = '5%';
                            progressText.textContent = 'Iniciando backtest...';
                            liveTradesBody.innerHTML = '';
                            consoleOutput.innerHTML = '';
                            liveTradeLog.style.display = 'none';
                            consoleLog.style.display = 'none';
                            document.getElementById('results').style.display = 'none';
                            document.getElementById('chart-card').style.display = 'none';
                            document.getElementById('strategy-card').style.display = 'none';
                            document.getElementById('ticker-card').style.display = 'none';
                            
                            // Reset running PnL footer
                            let cumulativePnl = 0;
                            let cumulativeTrades = 0;
                            let startTimeSec = 0;
                            runningPnlFooter.style.display = 'block';
                            footerPnl.textContent = '$0.00';
                            footerPnl.className = '';
                            footerTrades.textContent = '0 trades';
                            footerTime.textContent = '0s';
                            footerStatus.textContent = '⏳ Esperando...';

                            // Connect to SSE stream FIRST, then show console
                            const eventSource = new EventSource('/backtest-ui/stream');
                            
                            // Show console immediately
                            consoleLog.style.display = 'block';
                            addConsoleLog('🚀 Iniciando backtest...', 'info');
                            addConsoleLog('⏳ Conectando al servidor...', 'info');
                            
                            // Track cumulative equity
                            const equityPoints = [];
                            let allTrades = [];

                            // Handle ALL events (SSE without named events)
                            eventSource.onmessage = (e) => {
                                try {
                                    const data = JSON.parse(e.data);
                                    
                                    switch (data.type) {
                                        case 'start':
                                            progressText.textContent = `Cargando ${data.tickerCount} tickers (${data.hotTickers} hot + ${data.totalTickers - data.hotTickers} rest)...`;
                                            progressBar.style.width = '10%';
                                            addConsoleLog(`📂 Cargando ${data.tickerCount} tickers (${data.dateRange})`, 'info');
                                            addConsoleLog(`💵 Capital: $${data.capital.toLocaleString()} | Riesgo: 2%`, 'info');
                                            footerStatus.textContent = `📂 Cargando ${data.tickerCount} tickers...`;
                                            break;
                                            
                                        case 'trades':
                                            // Show live trade log if we have trades
                                            if (data.trades && data.trades.length > 0) {
                                                if (liveTradeLog.style.display === 'none') {
                                                    liveTradeLog.style.display = 'block';
                                                    addConsoleLog('📊 Primeras señales detectadas', 'success');
                                                }
                                                
                                                // Add new trades to the live table
                                                data.trades.forEach(trade => {
                                                    const tr = document.createElement('tr');
                                                    const pnlClass = trade.netPnl >= 0 ? 'positive' : 'negative';
                                                    const exitBadge = trade.exitReason === 'TP' ? 'badge-success' : 
                                                                     trade.exitReason === 'SL' ? 'badge-danger' : 'badge-warning';
                                                    const chartLink = trade.chartPath ? `<a href="/charts/${trade.chartPath}" target="_blank" style="color: #58a6ff; text-decoration: none;" title="Ver gráfico">📊</a>` : '';
                                                    
                                                    tr.innerHTML = `
                                                        <td>${trade.entryTime.substring(5)}</td>
                                                        <td><strong>${trade.ticker}</strong></td>
                                                        <td>${trade.strategy}</td>
                                                        <td>${trade.direction}</td>
                                                        <td style="font-size: 11px;">${trade.pattern}</td>
                                                        <td class="${pnlClass}">$${trade.netPnl.toFixed(2)}</td>
                                                        <td><span class="badge ${exitBadge}">${trade.exitReason}</span></td>
                                                        <td>${chartLink}</td>
                                                    `;
                                                    liveTradesBody.insertBefore(tr, liveTradesBody.firstChild);
                                                });
                                                
                                                allTrades.push(...data.trades);
                                                
                                                // Log new trades to console
                                                data.trades.forEach(trade => {
                                                    const pnlEmoji = trade.netPnl >= 0 ? '✅' : '❌';
                                                    const pnlSign = trade.netPnl >= 0 ? '+' : '';
                                                    addConsoleLog(`${pnlEmoji} ${trade.ticker} ${trade.strategy} ${trade.direction}: ${pnlSign}$${trade.netPnl.toFixed(2)} (${trade.exitReason})`, 
                                                        trade.netPnl >= 0 ? 'success' : 'error');
                                                });
                                                
                                                // Update cumulative PnL
                                                cumulativePnl += data.recentPnl || 0;
                                                cumulativeTrades += data.trades.length;
                                                
                                                // Update footer
                                                footerPnl.textContent = '$' + cumulativePnl.toFixed(2);
                                                footerPnl.className = cumulativePnl >= 0 ? 'positive' : 'negative';
                                                footerTrades.textContent = cumulativeTrades + ' trades';
                                            }
                                            
                                            // Update progress
                                            const totalTrades = data.totalTrades || 0;
                                            const elapsed = data.elapsedMs || 0;
                                            startTimeSec = Math.round(elapsed / 1000);
                                            progressText.textContent = `Escaneando... ${totalTrades} trades encontrados (${startTimeSec}s)`;
                                            progressBar.style.width = Math.min(90, 10 + (totalTrades / 5)) + '%';
                                            footerTime.textContent = startTimeSec + 's';
                                            footerStatus.textContent = 'Escaneando...';
                                            break;
                                            
                                        case 'progress_update':
                                            // Update every 10 seconds even without new trades
                                            const elapsedSec = data.elapsedSec || 0;
                                            const tradesFound = data.totalTrades || 0;
                                            progressText.textContent = `Escaneando... ${tradesFound} trades encontrados (${elapsedSec}s)`;
                                            footerTime.textContent = elapsedSec + 's';
                                            footerStatus.textContent = `Escaneando...`;
                                            
                                            // Show progress in console (every 30s to avoid flooding)
                                            if (elapsedSec > 0 && elapsedSec % 30 === 0) {
                                                const min = Math.floor(elapsedSec / 60);
                                                const sec = elapsedSec % 60;
                                                const timeStr = min > 0 ? `${min}m ${sec}s` : `${sec}s`;
                                                addConsoleLog(`⏳ Progreso: ${timeStr}, ${tradesFound} trades encontrados`, 'info');
                                            }
                                            break;
                                            
                                        case 'equity':
                                            equityPoints.push(data);
                                            break;
                                            
                                        case 'complete':
                                            const totalTime = Math.round(data.elapsedMs / 1000);
                                            progressBar.style.width = '100%';
                                            progressText.textContent = `¡Completo! ${data.totalTrades} trades en ${totalTime}s`;
                                            addConsoleLog(`🏁 Backtest completo: ${data.totalTrades} trades en ${totalTime}s`, 'success');
                                            addConsoleLog(`📊 PnL Final: $${data.totalPnl.toFixed(2)}`, data.totalPnl >= 0 ? 'success' : 'error');
                                            addConsoleLog(`📈 Win Rate: ${data.winRate.toFixed(1)}% | Profit Factor: ${data.profitFactor.toFixed(2)}`, 'info');
                                            
                                            // Update final footer
                                            cumulativePnl = data.totalPnl;
                                            cumulativeTrades = data.totalTrades;
                                            footerPnl.textContent = '$' + cumulativePnl.toFixed(2);
                                            footerPnl.className = cumulativePnl >= 0 ? 'positive' : 'negative';
                                            footerTrades.textContent = cumulativeTrades + ' trades';
                                            footerTime.textContent = totalTime + 's';
                                            footerStatus.textContent = '✅ Completo';
                                            
                                            // Display final results
                                            displayResults(data);
                                            
                                            // Clean up
                                            eventSource.close();
                                            btn.disabled = false;
                                            
                                            // Hide progress after 3 seconds
                                            setTimeout(() => {
                                                progressSection.style.display = 'none';
                                            }, 3000);
                                            break;
                                            
                                        case 'error':
                                            addConsoleLog(`❌ Error: ${data.error}`, 'error');
                                            alert('Error: ' + (data.error || 'Error desconocido'));
                                            eventSource.close();
                                            btn.disabled = false;
                                            progressSection.style.display = 'none';
                                            runningPnlFooter.style.display = 'none';
                                            break;
                                    }
                                } catch (err) {
                                    console.error('Error parsing SSE event:', err, e.data);
                                    addConsoleLog(`⚠️ Error parsing evento: ${err.message}`, 'warning');
                                }
                            };

                            // Handle connection errors
                            eventSource.onerror = (err) => {
                                console.error('SSE connection error:', err);
                                addConsoleLog('⚠️ Error de conexión SSE', 'warning');
                                eventSource.close();
                                btn.disabled = false;
                            };
                        }

                        function displayResults(data) {
                            // Show results card
                            document.getElementById('results').style.display = 'block';
                            document.getElementById('total-trades').textContent = data.totalTrades;
                            
                            const wr = data.winRate.toFixed(1) + '%';
                            document.getElementById('win-rate').textContent = wr;
                            document.getElementById('win-rate').className = 'stat-value ' + (data.winRate >= 50 ? 'positive' : 'negative');
                            
                            const pnl = '$' + data.totalPnl.toFixed(2);
                            document.getElementById('total-pnl').textContent = pnl;
                            document.getElementById('total-pnl').className = 'stat-value ' + (data.totalPnl >= 0 ? 'positive' : 'negative');
                            
                            document.getElementById('profit-factor').textContent = data.profitFactor.toFixed(2);
                            document.getElementById('max-dd').textContent = '$' + data.maxDrawdown.toFixed(2);

                            // Draw equity chart
                            if (data.equityCurve && data.equityCurve.length > 0) {
                                document.getElementById('chart-card').style.display = 'block';
                                drawEquityChart(data.equityCurve);
                            }

                            // Draw strategy table
                            if (data.byStrategy) {
                                document.getElementById('strategy-card').style.display = 'block';
                                drawStrategyTable(data.byStrategy);
                            }

                            // Draw ticker table
                            if (data.byTicker) {
                                document.getElementById('ticker-card').style.display = 'block';
                                drawTickerTable(data.byTicker);
                            }
                        }

                        function drawEquityChart(equityData) {
                            const ctx = document.getElementById('equity-chart').getContext('2d');
                            
                            if (equityChart) {
                                equityChart.destroy();
                            }

                            const labels = equityData.map(d => d.time);
                            const values = equityData.map(d => d.equity);
                            const isPositive = values[values.length - 1] >= values[0];

                            equityChart = new Chart(ctx, {
                                type: 'line',
                                data: {
                                    labels: labels,
                                    datasets: [{
                                        label: 'Equity',
                                        data: values,
                                        borderColor: isPositive ? '#3fb950' : '#f85149',
                                        backgroundColor: isPositive ? 'rgba(63, 185, 80, 0.1)' : 'rgba(248, 81, 73, 0.1)',
                                        fill: true,
                                        tension: 0.1,
                                        pointRadius: 0,
                                        borderWidth: 2
                                    }]
                                },
                                options: {
                                    responsive: true,
                                    maintainAspectRatio: false,
                                    plugins: {
                                        legend: { display: false },
                                        tooltip: {
                                            callbacks: {
                                                label: function(context) {
                                                    return 'Equity: $' + context.parsed.y.toLocaleString();
                                                }
                                            }
                                        }
                                    },
                                    scales: {
                                        x: {
                                            ticks: { color: '#8b949e', maxTicksLimit: 10 },
                                            grid: { color: '#21262d' }
                                        },
                                        y: {
                                            ticks: { color: '#8b949e', callback: v => '$' + v.toLocaleString() },
                                            grid: { color: '#21262d' }
                                        }
                                    }
                                }
                            });
                        }

                        function drawStrategyTable(byStrategy) {
                            const tbody = document.getElementById('strategy-table');
                            tbody.innerHTML = '';

                            Object.entries(byStrategy).forEach(([strategy, stats]) => {
                                const winRate = (stats.winRate || 0) * 100;
                                const totalPnl = stats.totalPnl || 0;
                                const pf = stats.profitFactor || 0;
                                const trades = stats.trades || 0;
                                const maxDD = stats.maxDrawdown || 0;

                                const tr = document.createElement('tr');
                                tr.innerHTML = `
                                    <td>${strategy}</td>
                                    <td>${trades}</td>
                                    <td class="${winRate >= 50 ? 'positive' : 'negative'}">${winRate.toFixed(1)}%</td>
                                    <td class="${totalPnl >= 0 ? 'positive' : 'negative'}">$${totalPnl.toFixed(2)}</td>
                                    <td>${pf.toFixed(2)}</td>
                                    <td>$${maxDD.toFixed(2)}</td>
                                    <td>
                                        <button class="btn btn-warning" onclick="improveStrategy('${strategy}')" ${trades < 2 ? 'disabled title="Need more trades"' : ''}>
                                            🔧 Improve
                                        </button>
                                    </td>
                                `;
                                tbody.appendChild(tr);
                            });
                        }

                        function drawTickerTable(byTicker) {
                            const tbody = document.getElementById('ticker-table');
                            tbody.innerHTML = '';

                            Object.entries(byTicker).forEach(([ticker, stats]) => {
                                const winRate = (stats.winRate || 0) * 100;
                                const totalPnl = stats.totalPnl || 0;
                                const pf = stats.profitFactor || 0;
                                const trades = stats.trades || 0;

                                const tr = document.createElement('tr');
                                tr.innerHTML = `
                                    <td><strong>${ticker}</strong></td>
                                    <td>${trades}</td>
                                    <td class="${winRate >= 50 ? 'positive' : 'negative'}">${winRate.toFixed(1)}%</td>
                                    <td class="${totalPnl >= 0 ? 'positive' : 'negative'}">$${totalPnl.toFixed(2)}</td>
                                    <td>${pf.toFixed(2)}</td>
                                `;
                                tbody.appendChild(tr);
                            });
                        }

                        async function improveStrategy(strategyName) {
                            currentStrategy = strategyName;
                            const modal = document.getElementById('improve-modal');
                            const loading = document.getElementById('improve-loading');
                            const results = document.getElementById('improve-results');
                            const retestBtn = document.getElementById('retest-btn');
                            
                            document.getElementById('improve-strategy-name').textContent = strategyName;
                            modal.classList.add('active');
                            loading.classList.add('active');
                            results.style.display = 'none';
                            retestBtn.style.display = 'none';
                            document.getElementById('comparison-section').style.display = 'none';

                            try {
                                const response = await fetch(`/backtest-ui/improve/${strategyName}`, { method: 'POST' });
                                const data = await response.json();
                                
                                if (data.success) {
                                    // Stats
                                    const statsDiv = document.getElementById('improve-stats');
                                    statsDiv.innerHTML = `
                                        <div class="stat"><div class="stat-value">${data.totalTrades}</div><div class="stat-label">Trades</div></div>
                                        <div class="stat"><div class="stat-value ${data.winRate >= 50 ? 'positive' : 'negative'}">${data.winRate.toFixed(1)}%</div><div class="stat-label">Win Rate</div></div>
                                        <div class="stat"><div class="stat-value ${data.totalPnl >= 0 ? 'positive' : 'negative'}">$${data.totalPnl.toFixed(2)}</div><div class="stat-label">PnL</div></div>
                                        <div class="stat"><div class="stat-value">$${data.avgWin.toFixed(2)}</div><div class="stat-label">Avg Win</div></div>
                                        <div class="stat"><div class="stat-value">$${data.avgLoss.toFixed(2)}</div><div class="stat-label">Avg Loss</div></div>
                                    `;
                                    
                                    // Exit reasons
                                    const exitDiv = document.getElementById('improve-exit-reasons');
                                    if (data.exitReasons) {
                                        exitDiv.innerHTML = Object.entries(data.exitReasons)
                                            .map(([reason, count]) => `<span class="badge badge-${reason === 'TP' ? 'success' : reason === 'SL' ? 'danger' : 'warning'}" style="margin: 2px;">${reason}: ${count}</span>`)
                                            .join('');
                                    }
                                    
                                    // Recommendations
                                    const recList = document.getElementById('improve-recommendations');
                                    recList.innerHTML = data.recommendations.map(r => `<li>${r}</li>`).join('');
                                    
                                    // Params
                                    const paramList = document.getElementById('improve-params');
                                    paramList.innerHTML = data.suggestedParams.map(p => `<li>${p}</li>`).join('');
                                    
                                    results.style.display = 'block';
                                    retestBtn.style.display = 'inline-block';
                                } else {
                                    alert(data.message || 'Analysis failed');
                                }
                            } catch (error) {
                                alert('Error analyzing strategy: ' + error.message);
                            } finally {
                                loading.classList.remove('active');
                            }
                        }

                        async function retestStrategy() {
                            const btn = document.getElementById('retest-btn');
                            btn.disabled = true;
                            btn.textContent = '⏳ Retesting...';

                            try {
                                const response = await fetch(`/backtest-ui/retest/${currentStrategy}`, { method: 'POST' });
                                const data = await response.json();

                                if (data.success) {
                                    document.getElementById('comparison-section').style.display = 'block';
                                    
                                    document.getElementById('comp-prev-trades').textContent = data.previous.trades;
                                    document.getElementById('comp-curr-trades').textContent = data.current.trades;
                                    document.getElementById('comp-trades-diff').textContent = (data.current.trades - data.previous.trades) > 0 ? '+' : '' + (data.current.trades - data.previous.trades);
                                    
                                    document.getElementById('comp-prev-wr').textContent = data.previous.winRate.toFixed(1) + '%';
                                    document.getElementById('comp-curr-wr').textContent = data.current.winRate.toFixed(1) + '%';
                                    const wrDiff = data.improvement.winRateDiff;
                                    document.getElementById('comp-wr-diff').textContent = (wrDiff > 0 ? '+' : '') + wrDiff.toFixed(1) + '%';
                                    document.getElementById('comp-wr-diff').className = wrDiff >= 0 ? 'positive' : 'negative';
                                    
                                    document.getElementById('comp-prev-pnl').textContent = '$' + data.previous.totalPnl.toFixed(2);
                                    document.getElementById('comp-curr-pnl').textContent = '$' + data.current.totalPnl.toFixed(2);
                                    const pnlDiff = data.improvement.pnlDiff;
                                    document.getElementById('comp-pnl-diff').textContent = '$' + pnlDiff.toFixed(2);
                                    document.getElementById('comp-pnl-diff').className = pnlDiff >= 0 ? 'positive' : 'negative';
                                }
                            } catch (error) {
                                alert('Error during retest: ' + error.message);
                            } finally {
                                btn.disabled = false;
                                btn.textContent = '🔄 Retest & Compare';
                            }
                        }

                        function closeModal() {
                            document.getElementById('improve-modal').classList.remove('active');
                            currentStrategy = '';
                        }

                        // Close modal on outside click
                        document.getElementById('improve-modal').addEventListener('click', function(e) {
                            if (e.target === this) closeModal();
                        });
                    </script>
                </body>
                </html>
                """;
    }
}
