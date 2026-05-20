package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.grid.GridSearchRequest;
import com.fgiaquinta.optionsquant.backtest.grid.GridSearchResult;
import com.fgiaquinta.optionsquant.backtest.grid.GridSearchService;
import com.fgiaquinta.optionsquant.backtest.grid.PromoteRiskRequest;
import com.fgiaquinta.optionsquant.backtest.grid.PromoteResult;
import com.fgiaquinta.optionsquant.backtest.grid.PromoteRiskService;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport.EquityPoint;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport.StrategyStats;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.BacktestAnalyzer;
import com.fgiaquinta.optionsquant.service.TickerService;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import com.fgiaquinta.optionsquant.service.TickerStrategyProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final GridSearchService gridSearchService;
    private final PromoteRiskService promoteRiskService;
    private final BacktestAnalyzer backtestAnalyzer;

    // Shared backtest running flag (for stop functionality)
    private final AtomicBoolean backtestRunning = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread currentBacktestThread = null;
    private final List<SseEmitter> activeEmitters = new CopyOnWriteArrayList<>();

    // Checkpoint file path for resume capability
    private static final Path CHECKPOINT_FILE = Path.of("backtest/checkpoint.txt");

    // Cached DateTimeFormatter for chart time display
    private static final DateTimeFormatter CHART_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId MADRID_ZONE = ZoneId.of("Europe/Madrid");

    // Event-driven progress tracker: replaces CSV polling for SSE progress updates.
    // The backtest thread updates this map directly; the SSE monitoring loop reads from it.
    private final ConcurrentHashMap<String, BacktestProgress> activeBacktests = new ConcurrentHashMap<>();

    /** When true, {@link com.fgiaquinta.optionsquant.service.BacktestUiScheduler} runs {@link #runScheduledBacktestTick()} on a fixed delay. */
    private final AtomicBoolean schedulerEnabled = new AtomicBoolean(false);
    private volatile double schedInitialCapital = 50_000;
    private volatile double schedRiskPct = 0.02;
    private volatile String schedTickerFilter = "";
    private volatile String schedTickerScope = "HOT";
    private volatile long lastScheduledRunEpochMs = 0L;

    /**
     * Trades from the last successful UI or scheduled backtest. {@link #improveStrategy} and {@link #retestStrategy}
     * use this first so analysis matches on-screen {@code byStrategy} even when {@code trades.csv} is stale, partially
     * written, or when strategy name matching against the file fails (encoding, spaces).
     */
    private volatile List<TradeRecord> lastCompletedBacktestTrades = List.of();

    /** Resolved ticker list and range from the last successful {@link #executeBacktestUiRun} — used to make /retest fast and comparable. */
    private volatile List<String> lastUiBacktestTickers = List.of();
    private volatile LocalDate lastUiBacktestFrom;
    private volatile LocalDate lastUiBacktestTo;

    @Value("${backtest.scheduler.fixed-delay-ms:3600000}")
    private long schedulerFixedDelayMs;

    /**
     * Mutable progress state shared between the backtest thread and SSE monitor.
     */
    private static class BacktestProgress {
        volatile int totalTrades = 0;
        volatile long elapsedMs = 0;
        volatile boolean done = false;
    }

    /**
     * Serves the main dashboard HTML page.
     * GET /backtest-ui - redirects to React SPA at /
     */
    @GetMapping
    public org.springframework.web.servlet.ModelAndView dashboard() {
        return new org.springframework.web.servlet.ModelAndView("redirect:/");
    }

    /**
     * SSE endpoint for streaming backtest progress in real-time.
     * GET /backtest-ui/stream
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamBacktest() {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L); // 10 min timeout
        activeEmitters.add(emitter);

        emitter.onCompletion(() -> activeEmitters.remove(emitter));
        emitter.onTimeout(() -> activeEmitters.remove(emitter));
        emitter.onError((e) -> activeEmitters.remove(emitter));

        try {
            Map<String, Object> init = new LinkedHashMap<>();
            init.put("type", "start");
            init.put("message", "Connected to backtest monitor");
            emitter.send(SseEmitter.event().name("start").data(init));
        } catch (IOException e) {
            activeEmitters.remove(emitter);
        }

        return emitter;
    }

    /**
     * Helper to send progress to all active UI clients.
     */
    private void broadcastProgress(Map<String, Object> progress) {
        if (activeEmitters.isEmpty()) return;

        String eventName = (String) progress.getOrDefault("type", "message");
        List<SseEmitter> deadEmitters = new ArrayList<>();

        // Use a background task for broadcasting to avoid blocking the backtest engine
        CompletableFuture.runAsync(() -> {
            for (SseEmitter emitter : activeEmitters) {
                try {
                    emitter.send(SseEmitter.event().name(eventName).data(progress));
                } catch (Exception e) {
                    deadEmitters.add(emitter);
                }
            }
            if (!deadEmitters.isEmpty()) {
                activeEmitters.removeAll(deadEmitters);
            }
        });
    }

    /**
     * Estimates the number of trade lines in a byte segment of the CSV file.
     * Uses ~100 bytes per trade line as a heuristic to avoid parsing the content.
     */
    private int estimateNewTradesInSegment(long byteCount) {
        return (int) Math.max(0, byteCount / 100);
    }

    /**
     * Reads new trades from CSV using byte-offset tracking.
     * Only reads bytes appended since the last poll, avoiding loading the entire file.
     */
    private List<Map<String, Object>> readNewTradesFromCsv(Path csvPath, int startIndex, long lastByteOffset) {
        List<Map<String, Object>> trades = new ArrayList<>();
        try {
            if (!Files.exists(csvPath)) return trades;
            long currentSize = Files.size(csvPath);
            if (currentSize <= lastByteOffset) return trades;

            try (FileChannel channel = FileChannel.open(csvPath, StandardOpenOption.READ)) {
                channel.position(lastByteOffset);
                long bytesToRead = currentSize - lastByteOffset;
                ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(bytesToRead, 1024 * 1024));
                int bytesRead = channel.read(buffer);
                if (bytesRead > 0) {
                    buffer.flip();
                    String newContent = StandardCharsets.UTF_8.decode(buffer).toString();
                    String[] newLines = newContent.split("\n");

                    // Skip partial last line (may be incomplete write)
                    int linesToProcess = newLines.length;
                    if (!newContent.endsWith("\n") && linesToProcess > 0) {
                        linesToProcess--;
                    }

                    for (int i = 0; i < linesToProcess; i++) {
                        String line = newLines[i].trim();
                        if (line.isEmpty() || line.startsWith("Ticker")) continue;
                        String[] parts = line.split(",");
                        if (parts.length >= 20) {
                            Map<String, Object> trade = new LinkedHashMap<>();
                            trade.put("ticker", parts[0].trim());
                            trade.put("strategy", parts[1].trim());
                            trade.put("direction", parts[2].trim());
                            trade.put("ep", parts[4].trim());
                            trade.put("xp", parts[6].trim());
                            trade.put("netPnl", Double.parseDouble(parts[12].trim()));
                            trade.put("exitReason", parts[8].trim());
                            trade.put("pattern", parts[15].trim());
                            String entryTime = parts[5].trim();
                            trade.put("entryTime", entryTime);
                            trade.put("exitTime", parts[7].trim());
                            if (entryTime.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
                                entryTime = entryTime.substring(0, 16);
                            }
                            String safeTicker = parts[0].trim().replaceAll("[^a-zA-Z0-9]", "_");
                            String safeStrategy = parts[1].trim().replaceAll("[^a-zA-Z0-9]", "_");
                            String chartFilename = String.format("%s_%s_%s_%s.html",
                                    safeTicker, safeStrategy, parts[2].trim(),
                                    entryTime.replace(" ", "_").replace(":", "-"));
                            trade.put("chartPath", chartFilename);
                            trades.add(trade);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error reading new trades from CSV: {}", e.getMessage(), e);
        }
        return trades;
    }

    private Map<String, Object> toTradePayload(TradeRecord trade) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticker", trade.ticker());
        payload.put("strategy", trade.strategy());
        payload.put("direction", trade.direction());
        payload.put("pattern", trade.candlestickPattern());
        payload.put("entryTime", trade.entryTime().format(TS_FMT));
        payload.put("exitTime", trade.exitTime().format(TS_FMT));
        payload.put("ep", String.format(Locale.US, "%.2f", trade.entryPrice()));
        payload.put("xp", String.format(Locale.US, "%.2f", trade.exitPrice()));
        payload.put("entryPrice", trade.entryPrice());
        payload.put("exitPrice", trade.exitPrice());
        payload.put("netPnl", trade.netPnl());
        payload.put("exitReason", trade.exitReason());
        payload.put("chartPath", buildChartFilename(trade));
        return payload;
    }

    private String buildChartFilename(TradeRecord trade) {
        String safeTicker = trade.ticker().replaceAll("[^a-zA-Z0-9]", "_");
        String safeStrategy = trade.strategy().replaceAll("[^a-zA-Z0-9]", "_");
        String signalTimeStr = trade.entryTime()
                .withZoneSameInstant(MADRID_ZONE)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return String.format("%s_%s_%s_%s.html",
                safeTicker,
                safeStrategy,
                trade.direction(),
                signalTimeStr.replace(" ", "_").replace(":", "-"));
    }

    /**
     * Core UI backtest run (blocking). Caller must own {@code backtestRunning} and clear it in {@code finally}.
     */
    private Map<String, Object> executeBacktestUiRun(double initialCapital, double riskPct, String tickerFilter, String tickerScope) {
        try {
            List<String> hotTickers = tickerService.getHotTickers();
            List<String> allTickers = tickerService.getTickerSymbols();
            List<String> orderedAllTickers = new ArrayList<>(hotTickers);
            allTickers.stream()
                    .filter(t -> !hotTickers.contains(t))
                    .forEach(orderedAllTickers::add);

            List<String> tickers = "HOT".equalsIgnoreCase(tickerScope)
                    ? new ArrayList<>(hotTickers)
                    : orderedAllTickers;

            if (tickerFilter != null && !tickerFilter.isBlank()) {
                Set<String> filterSet = Arrays.stream(tickerFilter.split(","))
                        .map(String::trim)
                        .map(String::toUpperCase)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
                tickers = tickers.stream()
                        .filter(filterSet::contains)
                        .collect(Collectors.toList());
                log.info("Ticker filter applied inside {} universe: {} tickers from '{}'", tickerScope, tickers.size(), tickerFilter);
            }

            if (tickers.isEmpty()) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("success", false);
                error.put("error", "No tickers matched the selected universe/filter.");
                return error;
            }

            LocalDate toDate = LocalDate.now();
            LocalDate fromDate = toDate.minusYears(1);

            log.info("Running backtest via web UI: {} tickers from {} universe, {} to {}",
                    tickers.size(), tickerScope.toUpperCase(Locale.ROOT), fromDate, toDate);

            BacktestConfig config = new BacktestConfig(
                    tickers, fromDate, toDate,
                    initialCapital, riskPct, 0.0008, 0.65,
                    3, TimeFrame.MIN_15, true, false,
                    0.0, 0.0, null,
                    LocalTime.of(9, 45),
                    LocalTime.of(10, 30),
                    LocalTime.of(13, 0),
                    null  // strategyFilter — run all strategies
            );

            final BacktestReport[] reportHolder = new BacktestReport[1];
            final Exception[] errorHolder = new Exception[1];
            Path tradesCsv = Path.of("backtest/trades.csv");
            currentBacktestThread = new Thread(() -> {
                try {
                    reportHolder[0] = backtestEngine.run(config, false, stopRequested, (t, s, d) -> {
                        if ("ALL".equals(t)) return;
                        Map<String, Object> progress = new LinkedHashMap<>();
                        progress.put("type", "ticker_progress");
                        progress.put("ticker", t);
                        progress.put("status", s);
                        progress.put("detail", d);
                        progress.put("time", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                        broadcastProgress(progress);
                    });
                } catch (Exception e) {
                    errorHolder[0] = e;
                }
            });
            currentBacktestThread.start();

            long tradesByteOffset = 0;
            int totalTradesSent = 0;
            while (currentBacktestThread.isAlive()) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                try {
                    long currentSize = Files.exists(tradesCsv) ? Files.size(tradesCsv) : 0;
                    if (currentSize > tradesByteOffset) {
                        List<Map<String, Object>> newTrades = readNewTradesFromCsv(tradesCsv, totalTradesSent, tradesByteOffset);
                        if (!newTrades.isEmpty()) {
                            Map<String, Object> tradesEvent = new LinkedHashMap<>();
                            tradesEvent.put("type", "trades");
                            tradesEvent.put("trades", newTrades);
                            tradesEvent.put("totalTrades", totalTradesSent + newTrades.size());
                            broadcastProgress(tradesEvent);
                            totalTradesSent += newTrades.size();
                        }
                        tradesByteOffset = currentSize;
                    }
                } catch (Exception e) {
                    log.debug("Error polling trades CSV: {}", e.getMessage());
                }
            }
            try {
                long currentSize = Files.exists(tradesCsv) ? Files.size(tradesCsv) : 0;
                if (currentSize > tradesByteOffset) {
                    List<Map<String, Object>> remaining = readNewTradesFromCsv(tradesCsv, totalTradesSent, tradesByteOffset);
                    if (!remaining.isEmpty()) {
                        Map<String, Object> tradesEvent = new LinkedHashMap<>();
                        tradesEvent.put("type", "trades");
                        tradesEvent.put("trades", remaining);
                        tradesEvent.put("totalTrades", totalTradesSent + remaining.size());
                        broadcastProgress(tradesEvent);
                    }
                }
            } catch (Exception e) {
                log.debug("Error in final trades flush: {}", e.getMessage());
            }
            currentBacktestThread.join(5000);
            currentBacktestThread = null;

            if (errorHolder[0] != null) {
                throw errorHolder[0];
            }

            BacktestReport report = reportHolder[0];
            if (report == null) {
                Map<String, Object> stopped = new LinkedHashMap<>();
                stopped.put("success", false);
                stopped.put("stopped", true);
                stopped.put("error", "Backtest was stopped by user");
                return stopped;
            }

            lastCompletedBacktestTrades = report.trades() == null ? List.of() : List.copyOf(report.trades());
            lastUiBacktestTickers = List.copyOf(tickers);
            lastUiBacktestFrom = fromDate;
            lastUiBacktestTo = toDate;

            List<Map<String, Object>> equityData = new ArrayList<>();
            for (EquityPoint point : report.equityCurve()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("time", point.timestamp().format(CHART_TIME_FMT));
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

            List<Map<String, Object>> trades = new ArrayList<>();
            for (TradeRecord trade : report.trades()) {
                trades.add(toTradePayload(trade));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("totalTrades", report.totalTrades());
            double winRatePct = report.winRate() * 100;
            result.put("winRate", winRatePct);
            result.put("winRatePct", winRatePct);
            double netPnl = report.finalCapital() - report.initialCapital();
            result.put("totalPnl", netPnl);
            result.put("netPnl", netPnl);
            result.put("profitFactor", report.profitFactor());
            result.put("maxDrawdown", report.maxDrawdown());
            result.put("maxDrawdownPct", report.maxDrawdownPct());
            result.put("initialCapital", report.initialCapital());
            result.put("finalCapital", report.finalCapital());
            result.put("totalReturnPct", report.totalReturnPct());
            result.put("winningTrades", report.winningTrades());
            result.put("losingTrades", report.losingTrades());
            result.put("equityCurve", equityData);
            result.put("trades", trades);
            result.put("byStrategy", byStrategy);
            result.put("byTicker", byTicker);
            result.put("tickerCount", tickers.size());
            result.put("hotTickers", hotTickers.size());
            result.put("tickerScope", tickerScope.toUpperCase(Locale.ROOT));

            log.info("Backtest complete via UI: {} trades, {}% WR, ${} PnL",
                    report.totalTrades(), String.format("%.1f", report.winRate() * 100), String.format("%.2f", report.finalCapital() - report.initialCapital()));

            return result;
        } catch (Exception e) {
            log.error("Backtest failed via UI: {}", e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return error;
        }
    }

    /**
     * Invoked by {@link com.fgiaquinta.optionsquant.service.BacktestUiScheduler} when auto-run is enabled.
     */
    public void runScheduledBacktestTick() {
        if (!schedulerEnabled.get()) {
            return;
        }
        if (!backtestRunning.compareAndSet(false, true)) {
            log.debug("Scheduled backtest skipped: a run is already in progress");
            return;
        }
        stopRequested.set(false);
        try {
            log.info("Scheduled backtest starting (capital={}, riskPct={}, scope={}, filter={})",
                    schedInitialCapital, schedRiskPct, schedTickerScope, schedTickerFilter);
            Map<String, Object> outcome = executeBacktestUiRun(schedInitialCapital, schedRiskPct, schedTickerFilter, schedTickerScope);
            lastScheduledRunEpochMs = System.currentTimeMillis();
            log.info("Scheduled backtest finished: success={} totalTrades={}",
                    outcome.get("success"), outcome.get("totalTrades"));
        } finally {
            backtestRunning.set(false);
            currentBacktestThread = null;
        }
    }

    private void applySchedulerBody(Map<String, Object> body) {
        if (body == null) {
            return;
        }
        Object en = body.get("enabled");
        if (en instanceof Boolean) {
            schedulerEnabled.set((Boolean) en);
        }
        Object ic = body.get("initialCapital");
        if (ic instanceof Number) {
            schedInitialCapital = ((Number) ic).doubleValue();
        }
        Object rp = body.get("riskPct");
        if (rp instanceof Number) {
            schedRiskPct = ((Number) rp).doubleValue();
        }
        Object tf = body.get("tickerFilter");
        if (tf instanceof String) {
            schedTickerFilter = (String) tf;
        } else if (tf != null) {
            schedTickerFilter = String.valueOf(tf);
        }
        Object ts = body.get("tickerScope");
        if (ts instanceof String) {
            schedTickerScope = (String) ts;
        } else if (ts != null) {
            schedTickerScope = String.valueOf(ts);
        }
    }

    private Map<String, Object> schedulerStatusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schedulerEnabled", schedulerEnabled.get());
        m.put("initialCapital", schedInitialCapital);
        m.put("riskPct", schedRiskPct);
        m.put("tickerFilter", schedTickerFilter != null ? schedTickerFilter : "");
        m.put("tickerScope", schedTickerScope != null ? schedTickerScope : "HOT");
        m.put("fixedDelayMs", schedulerFixedDelayMs);
        m.put("lastScheduledRunEpochMs", lastScheduledRunEpochMs);
        return m;
    }

    /**
     * GET /backtest-ui/scheduler — current auto-run flag and parameters used for scheduled runs.
     */
    @GetMapping("/scheduler")
    public ResponseEntity<Map<String, Object>> getScheduler() {
        return ResponseEntity.ok(schedulerStatusMap());
    }

    /**
     * POST /backtest-ui/scheduler — enable/disable auto-run and/or update parameters (JSON body).
     */
    @PostMapping("/scheduler")
    public ResponseEntity<Map<String, Object>> postScheduler(@RequestBody(required = false) Map<String, Object> body) {
        applySchedulerBody(body);
        return ResponseEntity.ok(schedulerStatusMap());
    }

    /**
     * API: Run a backtest with default parameters (hot tickers focus).
     * POST /backtest-ui/run
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runBacktest(
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct,
            @RequestParam(required = false) String tickerFilter,
            @RequestParam(defaultValue = "ALL") String tickerScope
    ) {
        if (!backtestRunning.compareAndSet(false, true)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", "A backtest is already running. Stop it first.");
            return ResponseEntity.ok(error);
        }

        stopRequested.set(false);
        try {
            Map<String, Object> map = executeBacktestUiRun(initialCapital, riskPct, tickerFilter, tickerScope);
            return ResponseEntity.ok(map);
        } finally {
            backtestRunning.set(false);
            currentBacktestThread = null;
        }
    }

    /**
     * GET /backtest-ui/running - Check if a backtest is currently running
     */
    @GetMapping("/running")
    public ResponseEntity<Map<String, Object>> isRunning() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", backtestRunning.get());
        result.put("schedulerEnabled", schedulerEnabled.get());
        result.put("fixedDelayMs", schedulerFixedDelayMs);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /backtest-ui/stop - Stop the currently running backtest
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopBacktest() {
        if (!backtestRunning.get()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("error", "No backtest is currently running");
            return ResponseEntity.ok(result);
        }

        log.info("Stop requested for running backtest");
        stopRequested.set(true);
        backtestRunning.set(false);

        Thread bt = currentBacktestThread;
        if (bt != null && bt.isAlive()) {
            bt.interrupt();
            log.info("Backtest thread interrupted");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Backtest stop requested");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/max-concurrent")
    public ResponseEntity<Map<String, Object>> getMaxConcurrent() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("maxConcurrentScans", backtestEngine.getMaxConcurrentScans());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/set-max-concurrent")
    public ResponseEntity<Map<String, Object>> setMaxConcurrent(@RequestParam int count) {
        if (count < 1 || count > 16) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Count must be between 1 and 16");
            return ResponseEntity.badRequest().body(result);
        }
        backtestEngine.setMaxConcurrentScans(count);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("maxConcurrentScans", count);
        result.put("message", "Max concurrent scans set to " + count);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /backtest-ui/checkpoint - Check if a checkpoint exists and get processed tickers
     */
    @GetMapping("/checkpoint")
    public ResponseEntity<Map<String, Object>> getCheckpoint() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (Files.exists(CHECKPOINT_FILE)) {
            try {
                List<String> lines = Files.readAllLines(CHECKPOINT_FILE);
                List<String> tickers = new ArrayList<>();
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        tickers.add(trimmed);
                    }
                }
                result.put("exists", true);
                result.put("processedCount", tickers.size());
                result.put("tickers", tickers);
            } catch (Exception e) {
                result.put("exists", false);
                result.put("error", "Failed to read checkpoint: " + e.getMessage());
            }
        } else {
            result.put("exists", false);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * POST /backtest-ui/checkpoint/clear - Clear the checkpoint file
     */
    @PostMapping("/checkpoint/clear")
    public ResponseEntity<Map<String, Object>> clearCheckpoint() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (Files.exists(CHECKPOINT_FILE)) {
                Files.delete(CHECKPOINT_FILE);
                result.put("success", true);
                result.put("message", "Checkpoint cleared");
            } else {
                result.put("success", true);
                result.put("message", "No checkpoint to clear");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Failed to clear checkpoint: " + e.getMessage());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * GET /backtest-ui/resume - SSE endpoint to resume backtest from checkpoint
     */
    @GetMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resumeBacktest(
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct
    ) {
        SseEmitter emitter = new SseEmitter(300_000L);

        if (!backtestRunning.compareAndSet(false, true)) {
            try {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("type", "error");
                error.put("error", "A backtest is already running");
                emitter.send(SseEmitter.event().name("error").data(error));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        if (!Files.exists(CHECKPOINT_FILE)) {
            try {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("type", "error");
                error.put("error", "No checkpoint found. Run a full backtest first.");
                emitter.send(SseEmitter.event().name("error").data(error));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            backtestRunning.set(false);
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // Load processed tickers from checkpoint
                List<String> processedTickers = new ArrayList<>();
                for (String line : Files.readAllLines(CHECKPOINT_FILE)) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        processedTickers.add(trimmed);
                    }
                }

                log.info("Resuming backtest from checkpoint: {} tickers already processed", processedTickers.size());

                // Send start event
                Map<String, Object> startEvent = new LinkedHashMap<>();
                startEvent.put("type", "start");
                startEvent.put("checkpointed", processedTickers.size());
                emitter.send(SseEmitter.event().name("start").data(startEvent));

                // Get all tickers, skip processed ones
                List<String> allTickers = tickerService.getTickerSymbols();
                List<String> remainingTickers = new ArrayList<>();
                for (String t : allTickers) {
                    if (!processedTickers.contains(t)) {
                        remainingTickers.add(t);
                    }
                }

                log.info("Resuming backtest: {} remaining tickers ({} already done)",
                        remainingTickers.size(), processedTickers.size());

                LocalDate toDate = LocalDate.now();
                LocalDate fromDate = toDate.minusYears(1);

                BacktestConfig config = new BacktestConfig(
                        remainingTickers, fromDate, toDate,
                        initialCapital, riskPct, 0.0008, 0.65,
                        3, TimeFrame.MIN_15, true, false,
                        0.0, 0.0, null,
                        LocalTime.of(9, 45),
                        LocalTime.of(10, 30),
                        LocalTime.of(13, 0),
                        null  // strategyFilter — run all strategies
                );

                final BacktestReport[] reportHolder = new BacktestReport[1];
                final Exception[] errorHolder = new Exception[1];
                stopRequested.set(false);
                currentBacktestThread = new Thread(() -> {
                    try {
                        reportHolder[0] = backtestEngine.run(config, true, stopRequested);
                    } catch (Exception e) {
                        errorHolder[0] = e;
                    }
                });
                currentBacktestThread.start();
                currentBacktestThread.join();
                currentBacktestThread = null;

                if (errorHolder[0] != null) {
                    throw errorHolder[0];
                }

                BacktestReport report = reportHolder[0];
                if (report == null) {
                    Map<String, Object> stoppedEvent = new LinkedHashMap<>();
                    stoppedEvent.put("type", "stopped");
                    stoppedEvent.put("message", "Resume was stopped by user");
                    emitter.send(SseEmitter.event().name("stopped").data(stoppedEvent));
                    emitter.complete();
                    return;
                }

                // Build and send results
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("type", "complete");
                result.put("totalTrades", report.totalTrades());
                result.put("winRate", report.winRate() * 100);
                result.put("totalPnl", report.finalCapital() - report.initialCapital());
                result.put("profitFactor", report.profitFactor());
                result.put("maxDrawdown", report.maxDrawdown());
                emitter.send(SseEmitter.event().name("complete").data(result));

                // Clear checkpoint on successful completion
                if (Files.exists(CHECKPOINT_FILE)) {
                    Files.delete(CHECKPOINT_FILE);
                }

                emitter.complete();
            } catch (Exception e) {
                log.error("Resume backtest failed: {}", e.getMessage(), e);
                try {
                    Map<String, Object> errorEvent = new LinkedHashMap<>();
                    errorEvent.put("type", "error");
                    errorEvent.put("error", e.getMessage());
                    emitter.send(SseEmitter.event().name("error").data(errorEvent));
                } catch (IOException ioEx) {
                    // ignore
                }
                emitter.completeWithError(e);
            } finally {
                backtestRunning.set(false);
            }
        });

        return emitter;
    }

    /**
     * API: Analyze a specific underperforming strategy and suggest improvements.
     * POST /backtest-ui/improve/{strategyName}
     * Optional {@code ticker} scopes stats to that symbol (same strategy); omit for all tickers in the last run / CSV.
     */
    @PostMapping("/improve/{strategyName}")
    public ResponseEntity<Map<String, Object>> improveStrategy(
            @PathVariable String strategyName,
            @RequestParam(required = false) String ticker
    ) {
        try {
            // Prefer in-memory trades from the last completed run (same source as byStrategy in the UI)
            List<Map<String, Object>> strategyTrades = resolveStrategyTradesForAnalysis(strategyName);
            final String filterTicker = ticker != null ? ticker.trim() : "";
            final boolean scopedToTicker = !filterTicker.isEmpty();
            if (scopedToTicker) {
                strategyTrades = filterTradesByTicker(strategyTrades, filterTicker);
            }

            if (strategyTrades.isEmpty()) {
                final Map<String, Object> analysis = new LinkedHashMap<>();
                analysis.put("success", false);
                analysis.put("message", scopedToTicker
                        ? ("No trades found for " + strategyName + " on ticker " + filterTicker.toUpperCase(Locale.ROOT)
                        + " (run a backtest that includes this pair, or check backtest/trades.csv)")
                        : ("No trades found for strategy: " + strategyName
                        + " (run a backtest first, or check backtest/trades.csv)"));
                analysis.put("analysisScope", scopedToTicker ? "TICKER_STRATEGY" : "STRATEGY_ALL_TICKERS");
                if (scopedToTicker) {
                    analysis.put("filterTicker", filterTicker.toUpperCase(Locale.ROOT));
                }
                return ResponseEntity.ok(analysis);
            }

            // Delegate stats + recommendation generation to the analysis service
            final Map<String, Object> stats = backtestAnalyzer.analyzeStrategyTrades(strategyTrades);

            if (scopedToTicker && strategyTrades.size() < 3) {
                @SuppressWarnings("unchecked")
                List<String> recs = (List<String>) stats.get("recommendations");
                recs.add("Low sample size for this ticker+strategy — interpret metrics with caution; compare strategy-wide stats.");
            }

            final Map<String, Object> analysis = new LinkedHashMap<>();
            analysis.put("success", true);
            analysis.put("strategy", strategyName);
            analysis.put("analysisScope", scopedToTicker ? "TICKER_STRATEGY" : "STRATEGY_ALL_TICKERS");
            if (scopedToTicker) {
                analysis.put("filterTicker", filterTicker.toUpperCase(Locale.ROOT));
            }
            analysis.put("lowSampleWarning", scopedToTicker && strategyTrades.size() < 3);
            analysis.put("totalTrades", strategyTrades.size());
            analysis.putAll(stats);

            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            log.error("Error analyzing strategy {}: {}", strategyName, e.getMessage());
            final Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    /**
     * Exhaustive grid search over {@code tpMultiplierDelta} / {@code slMultiplierDelta} (same engine as a normal backtest).
     * POST /backtest-ui/grid-search — synchronous v1: the HTTP request blocks until all cells finish or
     * {@code grid-search.timeout-ms} stops the run early (partial rows + CSV still returned). Rejects grids larger than
     * {@code grid-search.max-cells} with HTTP 400.
     */
    @PostMapping("/grid-search")
    public ResponseEntity<?> runGridSearch(@RequestBody GridSearchRequest request) {
        try {
            return ResponseEntity.ok(gridSearchService.run(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Promote grid-search deltas to persisted per-ticker+strategy ATR overrides ({@code dryRun=true} previews only).
     * POST /backtest-ui/promote-risk-params
     */
    @PostMapping("/promote-risk-params")
    public ResponseEntity<?> promoteRiskParams(@RequestBody PromoteRiskRequest request) {
        try {
            PromoteResult result = promoteRiskService.promote(request);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List strategy profiles that have TP/SL ATR overrides (and metadata) for UI management.
     * GET /backtest-ui/ticker-memory-profiles
     */
    @GetMapping("/ticker-memory-profiles")
    public ResponseEntity<List<Map<String, Object>>> listTickerMemoryRiskProfiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TickerStrategyProfile p : tickerMemory.getAllProfiles().values()) {
            if (p.getSlAtrMultOverride() == null && p.getTpAtrMultOverride() == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ticker", p.ticker);
            m.put("strategy", p.strategy);
            m.put("slAtrMultOverride", p.getSlAtrMultOverride());
            m.put("tpAtrMultOverride", p.getTpAtrMultOverride());
            m.put("lastUpdated", p.lastUpdated);
            m.put("updateCount", p.updateCount);
            out.add(m);
        }
        out.sort(Comparator
                .comparing((Map<String, Object> a) -> String.valueOf(a.getOrDefault("ticker", "")))
                .thenComparing(a -> String.valueOf(a.getOrDefault("strategy", ""))));
        return ResponseEntity.ok(out);
    }

    /**
     * Remove TP/SL overrides for one ticker+strategy (same key resolution as {@link TickerMemory}).
     * DELETE /backtest-ui/ticker-memory-profile?ticker=SPY&strategy=p5%20continuation
     */
    @DeleteMapping("/ticker-memory-profile")
    public ResponseEntity<?> deleteTickerMemoryRiskProfile(
            @RequestParam String ticker,
            @RequestParam String strategy) {
        boolean ok = tickerMemory.removeStrategyProfile(ticker, strategy);
        if (!ok) {
            return ResponseEntity.status(404).body(Map.of("error", "profile not found"));
        }
        return ResponseEntity.ok(Map.of("removed", true));
    }

    /**
     * API: Retest with improved parameters (simulated).
     * POST /backtest-ui/retest/{strategyName}
     *
     * This runs a new backtest and compares the results with the previous one.
     * By default ({@code matchLastRun=true}) uses the same ticker list and date range as the last
     * successful UI/scheduled backtest — much faster than scanning the full symbol universe.
     * Set {@code matchLastRun=false} for a full-universe 1y scan (slow).
     * Optional {@code tpMultiplierDelta} / {@code slMultiplierDelta} add to ATR multipliers in
     * {@link com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator}
     * for this run only (trial wider stops / targets — otherwise retest reproduces baseline).
     * Optional {@code ticker}: run the backtest only on this symbol (same date window as {@code matchLastRun} branch);
     * baseline comparison uses trades for that ticker only (aligned with scoped {@link #improveStrategy}).
     */
    @PostMapping("/retest/{strategyName}")
    public ResponseEntity<Map<String, Object>> retestStrategy(
            @PathVariable String strategyName,
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct,
            @RequestParam(defaultValue = "true") boolean matchLastRun,
            @RequestParam(defaultValue = "0") double tpMultiplierDelta,
            @RequestParam(defaultValue = "0") double slMultiplierDelta,
            @RequestParam(required = false) String ticker
    ) {
        try {
            log.info("Retesting strategy: {} capital=${} risk={}% matchLastRun={} tpΔ={} slΔ={} ticker={}",
                    strategyName, initialCapital, riskPct * 100, matchLastRun, tpMultiplierDelta, slMultiplierDelta, ticker);

            List<String> tickers;
            LocalDate fromDate;
            LocalDate toDate;
            String retestMode;

            if (matchLastRun && !lastUiBacktestTickers.isEmpty()) {
                tickers = new ArrayList<>(lastUiBacktestTickers);
                fromDate = lastUiBacktestFrom;
                toDate = lastUiBacktestTo;
                retestMode = "last_run";
                log.info("Retest using last UI run: {} tickers, {} → {}", tickers.size(), fromDate, toDate);
            } else if (matchLastRun) {
                // No cache (e.g. server restart): HOT-only is far cheaper than full universe
                tickers = new ArrayList<>(tickerService.getHotTickers());
                toDate = LocalDate.now();
                fromDate = toDate.minusYears(1);
                retestMode = "hot_fallback";
                log.info("Retest: no cached last-run tickers; using HOT-only ({} tickers), {} → {}",
                        tickers.size(), fromDate, toDate);
            } else {
                List<String> hotTickers = tickerService.getHotTickers();
                List<String> allTickers = tickerService.getTickerSymbols();
                tickers = new ArrayList<>(hotTickers);
                allTickers.stream()
                        .filter(t -> !hotTickers.contains(t))
                        .forEach(tickers::add);
                toDate = LocalDate.now();
                fromDate = toDate.minusYears(1);
                retestMode = "full_universe";
                log.info("Retest full universe: {} tickers, {} → {}", tickers.size(), fromDate, toDate);
            }

            String singleTickerKey = null;
            if (ticker != null && !ticker.isBlank()) {
                singleTickerKey = ticker.trim().toUpperCase(Locale.ROOT);
                tickers = new ArrayList<>(List.of(singleTickerKey));
                retestMode = switch (retestMode) {
                    case "last_run" -> "single_ticker_last_run";
                    case "hot_fallback" -> "single_ticker_hot_fallback";
                    case "full_universe" -> "single_ticker_full_window";
                    default -> "single_ticker_" + retestMode;
                };
                log.info("Retest scoped to single ticker {} — dates {} → {}", singleTickerKey, fromDate, toDate);
            }

            if (tickers.isEmpty()) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("success", false);
                err.put("error", "No tickers available for retest.");
                return ResponseEntity.ok(err);
            }

            BacktestConfig config = new BacktestConfig(
                    tickers, fromDate, toDate,
                    initialCapital, riskPct, 0.0008, 0.65,
                    3, TimeFrame.MIN_15, true, false,
                    tpMultiplierDelta, slMultiplierDelta, null,
                    LocalTime.of(9, 45),
                    LocalTime.of(10, 30),
                    LocalTime.of(13, 0),
                    null  // strategyFilter — run all strategies
            );

            BacktestReport report = backtestEngine.run(config);

            // Read previous strategy trades for comparison (same resolution as /improve); optional ticker filter
            List<Map<String, Object>> previousTrades = resolveStrategyTradesForAnalysis(strategyName);
            if (singleTickerKey != null) {
                previousTrades = filterTradesByTicker(previousTrades, singleTickerKey);
            }

            // Delegate comparison computation to the analysis service
            final StrategyStats currentStats = findStrategyStats(report, strategyName);
            final Map<String, Object> comparison = backtestAnalyzer.buildStrategyComparison(previousTrades, currentStats);

            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("strategy", strategyName);
            result.putAll(comparison);
            result.put("retestMode", retestMode);
            result.put("retestTickerCount", tickers.size());
            result.put("retestFrom", fromDate.toString());
            result.put("retestTo", toDate.toString());
            result.put("appliedTpMultiplierDelta", tpMultiplierDelta);
            result.put("appliedSlMultiplierDelta", slMultiplierDelta);
            if (singleTickerKey != null) {
                result.put("singleTickerScoped", true);
                result.put("filterTicker", singleTickerKey);
            } else {
                result.put("singleTickerScoped", false);
            }

            @SuppressWarnings("unchecked")
            final double prevPnl = ((Map<String, Object>) result.get("previous")) != null
                    ? (double) ((Map<String, Object>) result.get("previous")).getOrDefault("totalPnl", 0.0) : 0.0;
            @SuppressWarnings("unchecked")
            final double currPnl = ((Map<String, Object>) result.get("current")) != null
                    ? (double) ((Map<String, Object>) result.get("current")).getOrDefault("totalPnl", 0.0) : 0.0;
            log.info("Retest complete for {}: Before PnL=${}, After PnL=${}, Diff=${}",
                    strategyName, String.format("%.2f", prevPnl), String.format("%.2f", currPnl),
                    String.format("%.2f", currPnl - prevPnl));

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Retest failed for {}: {}", strategyName, e.getMessage(), e);
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("error", e.getMessage());
            return ResponseEntity.ok(error);
        }
    }

    private static String normalizeStrategyKey(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean strategyNamesMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return normalizeStrategyKey(a).equals(normalizeStrategyKey(b));
    }

    /**
     * Trades for analysis: last completed backtest in this JVM first, then trades.csv.
     */
    private List<Map<String, Object>> resolveStrategyTradesForAnalysis(String strategyName) {
        List<Map<String, Object>> fromRun = filterTradesFromRecords(lastCompletedBacktestTrades, strategyName);
        if (!fromRun.isEmpty()) {
            return fromRun;
        }
        return readStrategyTrades(strategyName);
    }

    private List<Map<String, Object>> filterTradesFromRecords(List<TradeRecord> trades, String strategyName) {
        if (trades == null || trades.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TradeRecord t : trades) {
            if (strategyNamesMatch(t.strategy(), strategyName)) {
                out.add(tradeRecordToAnalysisMap(t));
            }
        }
        return out;
    }

    private List<Map<String, Object>> filterTradesByTicker(List<Map<String, Object>> trades, String ticker) {
        String key = ticker.trim().toUpperCase(Locale.ROOT);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : trades) {
            Object tv = row.get("ticker");
            if (tv != null && key.equals(String.valueOf(tv).trim().toUpperCase(Locale.ROOT))) {
                out.add(row);
            }
        }
        return out;
    }

    private Map<String, Object> tradeRecordToAnalysisMap(TradeRecord t) {
        Map<String, Object> trade = new LinkedHashMap<>();
        trade.put("ticker", t.ticker());
        trade.put("strategy", t.strategy());
        trade.put("direction", t.direction());
        trade.put("entryPrice", t.entryPrice());
        trade.put("entryTime", t.entryTime().format(TS_FMT));
        trade.put("exitPrice", t.exitPrice());
        trade.put("exitTime", t.exitTime().format(TS_FMT));
        trade.put("exitReason", t.exitReason());
        trade.put("netPnl", t.netPnl());
        trade.put("pattern", t.candlestickPattern());
        return trade;
    }

    private StrategyStats findStrategyStats(BacktestReport report, String strategyName) {
        Map<String, StrategyStats> map = report.byStrategy();
        if (map == null) {
            return null;
        }
        StrategyStats direct = map.get(strategyName);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, StrategyStats> e : map.entrySet()) {
            if (strategyNamesMatch(e.getKey(), strategyName)) {
                return e.getValue();
            }
        }
        return null;
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
                if (parts.length >= 20 && strategyNamesMatch(parts[1].trim(), strategyName)) {
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

}
