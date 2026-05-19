package com.fgiaquinta.optionsquant.controller;

import com.ib.client.Order;
import com.ib.client.TimeCondition;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.dto.ExternalPositionDto;
import com.fgiaquinta.optionsquant.dto.PositionSnapshot;
import com.fgiaquinta.optionsquant.dto.ScanScoreBreakdown;
import com.fgiaquinta.optionsquant.dto.ScanScoresResponse;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.ScanResult;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.trading.ConditionBuilder;
import com.fgiaquinta.optionsquant.trading.OrderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/**
 * Live Mode Dashboard and API.
 * Access at: http://localhost:9090/live-ui
 */
@Slf4j
@RestController
@RequestMapping("/live-ui")
public class LiveModeController {

    private static final Duration LIVE_SIGNAL_MAX_AGE = Duration.ofMinutes(30);
    private static final ZoneId SPAIN_TZ = ZoneId.of("Europe/Madrid");
    private static final java.time.format.DateTimeFormatter SPAIN_TIME_FMT = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    private final StrategyScannerService scannerService;
    private final IbkrProperties ibkrProperties;
    private final TradingService tradingService;
    private final TickerService tickerService;
    private final AccountManager accountManager;
    private final IbkrService ibkrService;
    private final OrderExecutionService orderExecutionService;
    private final MarketCalendarService marketCalendarService;
    private final com.fgiaquinta.optionsquant.service.MarketScanner marketScanner;
    private final ScannerProperties scannerProperties;
    private final MacroEnvironmentFilter macroFilter;
    private final ScanPrioritizationService scanPrioritizationService;

    /** Base directory for replay-signal JSONL files. Overridable in tests via ReflectionTestUtils. */
    @org.springframework.beans.factory.annotation.Value("${replay.signal-dir:data}")
    String replaySignalDir = "data";

    /** Filename pattern for replay-signal JSONL files. Args: date, runId. Overridable in tests via ReflectionTestUtils. */
    @org.springframework.beans.factory.annotation.Value("${replay.signals-file-pattern:replay-signals-%s-%s.jsonl}")
    String replaySignalsFilePattern = "replay-signals-%s-%s.jsonl";

    // Live-replay-mode — optional; null until services are wired in the context.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReplayService replayService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReplayClock replayClock;

    /**
     * Envelope holding the latest scan-score breakdown and the instant it was computed.
     * Computed at scan-start by both entrypoints ({@link #triggerScan()} and
     * {@link com.fgiaquinta.optionsquant.service.MarketScanner#scanAndExecute()}).
     * Single-writer pattern: both scan threads write before the scan loop runs; reads are lock-free.
     * Pattern A: both call sites invoke {@link #setScanScores(java.util.Map)} before their scan loop.
     * {@code scanStartedAt} is {@code null} until the first scan runs.
     */
    private final AtomicReference<ScanScoresResponse> latestScanScores =
            new AtomicReference<>(new ScanScoresResponse(Map.of(), null));

    // Live scanning state
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean isAutoScan = new AtomicBoolean(false);
    private final AtomicBoolean stopScanRequested = new AtomicBoolean(false);
    private final AtomicBoolean mockMarketOpen = new AtomicBoolean(false);
    private final AtomicInteger currentTickerIndex = new AtomicInteger(0);
    private final AtomicInteger totalTickers = new AtomicInteger(0);
    private final AtomicReference<String> currentTicker = new AtomicReference<>("");
    private final AtomicReference<List<String>> scanningTickers = new AtomicReference<>(Collections.emptyList());
    /** Full universe (all 503) set once at scan start — unaffected by HOT/ALL scope or comma filter. */
    private final AtomicReference<List<String>> allScanTickers = new AtomicReference<>(Collections.emptyList());
    /** Throttle AccountManager reconnect attempts from UI polling */
    private final AtomicLong lastTwsReconnectAttemptMs = new AtomicLong(0);
    private final CopyOnWriteArrayList<Signal> liveSignals = new CopyOnWriteArrayList<>();
    /** Separate bucket for signals emitted during live-replay-mode — keeps live grid frozen during replay. */
    private final CopyOnWriteArrayList<Signal> replaySignals = new CopyOnWriteArrayList<>();
    /** Wall-clock instant when each ticker row was last added/replaced in {@link #liveSignals} (for UI "signal found"). */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> signalFoundAt = new java.util.concurrent.ConcurrentHashMap<>();
    // Manual close requests: ticker → ClosedTradeInfo
    private final java.util.concurrent.ConcurrentHashMap<String, ClosedTradeInfo> closedTrades = new java.util.concurrent.ConcurrentHashMap<>();
    // Manual executions: ticker → ExecutedTradeInfo
    private final java.util.concurrent.ConcurrentHashMap<String, ExecutedTradeInfo> executedTrades = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Tickers for which a 14:50 ET conditional close has already been scheduled.
     * ConcurrentHashMap.newKeySet() is used — NOT HashSet — to be safe under concurrent UI polling.
     * Cleared on restart (not persisted); idempotency guard prevents duplicate TWS orders.
     */
    private final java.util.Set<String> scheduled1450Tickers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Per-ticker scan timing for SCAN STARTED / SCAN FINISHED columns
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> tickerScanStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> tickerScanEndTimes = new java.util.concurrent.ConcurrentHashMap<>();
    // Scan activity log: shows what's being scanned in real-time
    private final CopyOnWriteArrayList<ScanActivity> scanActivity = new CopyOnWriteArrayList<>();
    private final AtomicLong lastScanTime = new AtomicLong(0);
    private final AtomicLong lastScanDuration = new AtomicLong(0);
    private final AtomicInteger signalsToday = new AtomicInteger(0);
    private final AtomicBoolean extendedHoursEnabled = new AtomicBoolean(true);
    // Runtime-overridable settings (survive scan but reset on restart)
    private volatile boolean runtimeAutoExecute;
    private volatile boolean runtimeMacroFilterEnabled = true;
    private volatile double runtimeRiskPct;
    private final AtomicReference<String> liveTickerFilter = new AtomicReference<>("");
    private final AtomicReference<String> liveTickerScope  = new AtomicReference<>("HOT");
    private Thread scanThread = null;

    public record ScanActivity(String time, String ticker, String status, String detail, String scanStarted, String scanEnded, String duration) {}
    public record ClosedTradeInfo(String ticker, double closePrice, String closeTime, String exitReason) {}
    public record ExecutedTradeInfo(String ticker, String executeTime, boolean success, String message, Integer orderId, Integer tpOrderId, Integer slOrderId) {}
    public record ReplaySummary(int totalSignals, int uniqueTickers, ZonedDateTime firstSignalAt, ZonedDateTime lastSignalAt) {}

    @SuppressWarnings("this-escape")
    public LiveModeController(StrategyScannerService scannerService,
                              IbkrProperties ibkrProperties,
                              TradingService tradingService,
                              TickerService tickerService,
                              AccountManager accountManager,
                              IbkrService ibkrService,
                              OrderExecutionService orderExecutionService,
                              MarketCalendarService marketCalendarService,
                              @Lazy com.fgiaquinta.optionsquant.service.MarketScanner marketScanner,
                              ScannerProperties scannerProperties,
                              MacroEnvironmentFilter macroFilter,
                              ScanPrioritizationService scanPrioritizationService) {
        this.scannerService = scannerService;
        this.ibkrProperties = ibkrProperties;
        this.tradingService = tradingService;
        this.tickerService = tickerService;
        this.accountManager = accountManager;
        this.ibkrService = ibkrService;
        this.orderExecutionService = orderExecutionService;
        this.marketCalendarService = marketCalendarService;
        this.marketScanner = marketScanner;
        this.scannerProperties = scannerProperties;
        this.macroFilter = macroFilter;
        this.scanPrioritizationService = scanPrioritizationService;
        
        // Initialize runtime-overridable settings from config
        this.runtimeAutoExecute = ibkrProperties.autoExecute();
        this.runtimeRiskPct = ibkrProperties.riskPerTradePct();

        // CRITICAL: Reset all scanning state on startup to recover from any previous stuck state
        isScanning.set(false);
        stopScanRequested.set(false);
        currentTicker.set("");
        currentTickerIndex.set(0);
        totalTickers.set(0);
        scanActivity.clear();
        liveSignals.clear();
        signalsToday.set(0);
        // Also reset scanner service progress counters to avoid stale data in /status
        scannerService.resetProgress();
        log.info("LiveModeController initialized - scanning state reset to clean");
        
        // Wire stop flag into scanner for immediate interruption
        scannerService.setStopRequestedSupplier(stopScanRequested::get);
        // Wire scan activity callback for real-time ticker tracking in feed
        scannerService.setScanActivityCallback(this::addTickerScanActivity);
        // Wire scan complete callback to mark tickers as done in feed
        scannerService.setScanCompleteCallback(this::addTickerScanComplete);
    }

    /**
     * Fires once, immediately after the application context is fully started.
     *
     * <p>If the app restarted with zero tracked trades but TWS already reports open positions,
     * ALL positions will appear as "external" until new trades are executed. This WARN helps
     * the operator understand why the external-positions panel is non-empty after a restart.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onAppReadyCheckExternalPositions() {
        if (executedTrades.isEmpty() && accountManager.getPositionsSnapshot().size() > 0) {
            int n = accountManager.getPositionsSnapshot().size();
            log.warn("Startup detected {} TWS positions with no app-tracked trades — " +
                    "all positions will display as external. This is expected after restart.", n);
        } else {
            log.debug("Startup external-positions check: executedTrades={}, snapshotSize={}",
                    executedTrades.size(), accountManager.getPositionsSnapshot().size());
        }
    }

    /**
     * Returns all TWS positions that were NOT opened by this application.
     *
     * <p>A position is considered "external" when its ticker (symbol) is absent from
     * {@link #executedTrades}. The snapshot itself is the authoritative source; entries
     * in {@code executedTrades} are used only as a filter mask.
     *
     * @return 200 OK with {@code { "positions": [...] }} or 503 if TWS is disconnected
     */
    @GetMapping("/external-positions")
    public ResponseEntity<Map<String, Object>> getExternalPositions() {
        if (!accountManager.isConnected()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "TWS connection not available"));
        }

        List<ExternalPositionDto> positions = accountManager.getPositionsSnapshot()
                .entrySet().stream()
                .filter(e -> !executedTrades.containsKey(e.getKey()))
                .map(e -> {
                    var snap = e.getValue();
                    return new ExternalPositionDto(
                            snap.symbol(),
                            snap.secType(),
                            snap.quantity(),
                            snap.avgCost(),
                            snap.snapshotAt().toString(),
                            "external"
                    );
                })
                .toList();

        log.debug("GET /external-positions — {} external position(s) returned (snapshot size={}, executedTrades size={})",
                positions.size(), accountManager.getPositionsSnapshot().size(), executedTrades.size());

        return ResponseEntity.ok(Map.of("positions", positions));
    }

    /**
     * Immediately closes an external (non-app-tracked) position with a market SELL order.
     *
     * <p>Validation:
     * <ul>
     *   <li>TWS must be connected — 503 otherwise</li>
     *   <li>Ticker must exist in the position snapshot — 404 otherwise</li>
     *   <li>Ticker must NOT be in {@link #executedTrades} (app-tracked) — 404 otherwise</li>
     * </ul>
     *
     * @param ticker Ticker symbol of the external position to close (path variable)
     * @return 200 with {@code { "message": "Market SELL placed", "orderId": n }} on success
     */
    @PostMapping("/external-positions/{ticker}/close")
    public ResponseEntity<Map<String, Object>> closeExternalPosition(@PathVariable String ticker) {
        if (!accountManager.isConnected()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "TWS connection not available"));
        }

        Map<String, PositionSnapshot> snapshot = accountManager.getPositionsSnapshot();
        if (!snapshot.containsKey(ticker) || executedTrades.containsKey(ticker)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "position no longer exists"));
        }

        PositionSnapshot pos = snapshot.get(ticker);
        int orderId = orderExecutionService.placeMarketSellExternal(pos.contract(), pos.quantity());
        accountManager.removePositionFromSnapshot(ticker);
        log.info("closeExternalPosition: placed market SELL for {} — orderId={}", ticker, orderId);
        return ResponseEntity.ok(Map.of("message", "Market SELL placed", "orderId", orderId));
    }

    /**
     * Schedules a conditional close at 14:50 ET for an external (non-app-tracked) position.
     * The close order is a market SELL attached to a {@link TimeCondition} set at 14:50:00 US/Eastern.
     *
     * <p>Idempotent: a second call for the same ticker returns 409 CONFLICT without placing a second order.
     *
     * <p>Validation:
     * <ul>
     *   <li>TWS must be connected — 503 otherwise</li>
     *   <li>Ticker must exist in snapshot and NOT be app-tracked — 404 otherwise</li>
     *   <li>Ticker must not already have a 14:50 close scheduled — 409 otherwise</li>
     * </ul>
     *
     * @param ticker Ticker symbol to schedule for 14:50 close (path variable)
     * @return 201 CREATED with {@code { "orderId": n, "message": "14:50 close scheduled" }} on success
     */
    @PostMapping("/external-positions/{ticker}/schedule-close-1450")
    public ResponseEntity<Map<String, Object>> scheduleClose1450(@PathVariable String ticker) {
        if (!accountManager.isConnected()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "TWS connection not available"));
        }

        Map<String, PositionSnapshot> snapshot = accountManager.getPositionsSnapshot();
        if (!snapshot.containsKey(ticker) || executedTrades.containsKey(ticker)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "position no longer exists"));
        }

        if (scheduled1450Tickers.contains(ticker)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "14:50 close already scheduled for " + ticker));
        }

        PositionSnapshot pos = snapshot.get(ticker);
        Order sellOrder = OrderFactory.createMarketOrder(0, "SELL", pos.quantity());
        TimeCondition condition = ConditionBuilder.createClose1450Condition();
        int orderId = orderExecutionService.placeConditionalOrder(pos.contract(), sellOrder, condition);

        scheduled1450Tickers.add(ticker);
        log.info("scheduleClose1450: conditional order placed for {} — orderId={}", ticker, orderId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("orderId", orderId, "message", "14:50 close scheduled"));
    }

    @GetMapping("/market-status")
    public ResponseEntity<Map<String, Object>> getMarketStatus() {
        ZonedDateTime nowEt = marketCalendarService.nowET();
        boolean open = marketCalendarService.isMarketOpen(nowEt);
        boolean regular = marketCalendarService.isRegularMarketHours(nowEt);

        String session;
        if (regular) session = "REGULAR";
        else if (open) session = "OPEN (EXT)";
        else session = "CLOSED";

        // Use regular market open for countdown (9:30 AM ET = 3:30 PM Spain in EDT)
        // instead of pre-market open (4:00 AM ET = 10:00 AM Spain)
        ZonedDateTime nextOpen = marketCalendarService.getNextRegularMarketOpen();
        long seconds = Math.max(0, Duration.between(nowEt, nextOpen).getSeconds());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", session);
        result.put("nowEt", nowEt.toString());
        result.put("nextOpenEt", nextOpen.toString());
        result.put("secondsToNextOpen", seconds);
        return ResponseEntity.ok(result);
    }

    @GetMapping
    public org.springframework.web.servlet.ModelAndView dashboard() {
        return new org.springframework.web.servlet.ModelAndView("redirect:/");
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("isScanning", isScanning.get());
        status.put("isAutoScan", isAutoScan.get());
        status.put("stopScanRequested", stopScanRequested.get());
        status.put("currentTicker", currentTicker.get());
        status.put("currentTickerIndex", currentTickerIndex.get());
        status.put("totalTickers", totalTickers.get());
        status.put("lastScanTime", lastScanTime.get());
        status.put("lastScanDuration", lastScanDuration.get());
        status.put("signalsToday", signalsToday.get());
        status.put("extendedHoursEnabled", extendedHoursEnabled.get());
        status.put("autoExecute", runtimeAutoExecute);
        status.put("macroFilterEnabled", runtimeMacroFilterEnabled);
        status.put("riskPct", Math.round(runtimeRiskPct * 100 * 10.0) / 10.0);
        status.put("liveTickerFilter", liveTickerFilter.get());
        status.put("liveTickerScope", liveTickerScope.get());
        status.put("twsConnected", accountManager.isConnected());

        // Include hot tickers list for UI badge display (same order as scan)
        List<String> hotTickers = tickerService.getHotTickers();
        status.put("hotTickersList", hotTickers);
        status.put("schedulerEnabled", marketScanner.isSchedulerEnabled());
        status.put("mockMarketOpen", mockMarketOpen.get());

        // Add max concurrent scans setting
        status.put("maxConcurrentScans", scannerService.getMaxConcurrentScans());
        status.put("scannerConcurrentMode", scannerProperties.concurrentMode().name());
        status.put("scannerPrioritizationMode", scannerProperties.prioritizationMode().name());
        status.put("scannerHybridFundamentalWeight", scannerProperties.hybridFundamentalWeight());
        status.put("scannerHybridMemoryWeight", scannerProperties.hybridMemoryWeight());
        status.put("scannerExclusiveSchedulerLockWaitMs", scannerProperties.exclusiveScanSchedulerLockWaitMs());
        status.put("scannerLivePreemptWaitMs", scannerProperties.livePreemptWaitMs());

        // Add scanner progress info
        status.put("scannerScanned", scannerService.getScannedCount());
        status.put("scannerBatchLabel", scannerService.getCurrentBatchLabel());
        status.put("scannerTotal", scannerService.getTotalToScan());
        status.put("scanningTickers", scanningTickers.get());
        status.put("allScanTickers", allScanTickers.get());
        status.put("exclusiveScanLockHeld", scannerService.isScanAllLockHeld());
        status.put("exclusiveScanOwnerThread", scannerService.getScanOwnerThreadLabel());

        ZonedDateTime nowSpain = ZonedDateTime.now(SPAIN_TZ);
        int currentHour = nowSpain.getHour();
        boolean isMarketHours = currentHour >= 10 && currentHour < 22 && nowSpain.getDayOfWeek().getValue() <= 5;
        status.put("marketHours", isMarketHours);
        status.put("currentTime", nowSpain.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        status.put("timezone", "Europe/Madrid");
        status.put("macroRegime", macroFilter.getRegime().name());
        status.put("macroMomentum", macroFilter.getMomentum().name());
        status.put("macroSummary", macroFilter.getAnalysisString());

        return ResponseEntity.ok(status);
    }

    @GetMapping("/signals")
    public ResponseEntity<Map<String, Object>> getSignals() {
        boolean replayActive = replayClock != null && replayClock.isActive();
        List<Signal> sourceSignals = replayActive ? replaySignals : liveSignals;
        List<Map<String, Object>> signalData = sourceSignals.stream()
                .filter(s -> replayActive || !isLiveSignalOlderThanMaxAge(s) || hasOpenExecutedPosition(s.ticker()))
                .map(s -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("ticker", s.ticker());
                    map.put("strategy", s.strategy());
                    map.put("direction", s.direction());
                    map.put("currentPrice", s.currentPrice());
                    map.put("timestamp", s.timestamp());
                    Instant found = signalFoundAt.get(s.ticker().toUpperCase(Locale.ROOT));
                    map.put("signalFoundAt", found != null ? found.toString() : null);
                    map.put("tradePlan", s.tradePlan());
                    map.put("candlestickPattern", s.candlestickPattern());
                    map.put("newsBias", s.newsBias() != null ? s.newsBias().name() : "NEUTRAL");
                    map.put("earningsAlert", s.earningsAlert());

                    ExecutedTradeInfo exec = executedTrades.get(s.ticker());
                    if (exec != null) {
                        map.put("executeTime", exec.executeTime());
                        map.put("tradeStatus", exec.success() ? "EXECUTED" : "FAILED");
                        map.put("orderId", exec.orderId());
                    }
                    return map;
                }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signals", signalData);
        result.put("count", signalData.size());
        result.put("signalsToday", signalsToday.get());
        result.put("closedTrades", closedTrades);
        result.put("executedTrades", executedTrades);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/scan-activity")
    public ResponseEntity<Map<String, Object>> getScanActivity() {
        Map<String, Object> result = new LinkedHashMap<>();
        // Parallel scans finish out of order; sort by HOT list priority then ticker for a stable UI
        result.put("activity", scanActivitySortedByHotPriority());
        result.put("isScanning", isScanning.get());
        result.put("batchLabel", scannerService.getCurrentBatchLabel());
        result.put("scanned", scannerService.getScannedCount());
        result.put("total", scannerService.getTotalToScan());
        result.put("lastScanTime", lastScanTime.get());
        Map<String, String> startTimesFormatted = new LinkedHashMap<>();
        tickerScanStartTimes.forEach((t, instant) -> startTimesFormatted.put(t, instant.atZone(SPAIN_TZ).format(SPAIN_TIME_FMT)));
        result.put("scanStartTimes", startTimesFormatted);
        result.put("scanEndTimes", tickerScanEndTimes);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tickers")
    public ResponseEntity<Map<String, Object>> getTickers() {
        List<String> allTickers = tickerService.getTickerSymbols();
        List<String> hotTickers = tickerService.getHotTickers();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allTickers", allTickers);
        result.put("hotTickers", hotTickers);
        result.put("total", allTickers.size());
        result.put("scanning", isScanning.get());
        result.put("currentTicker", currentTicker.get());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/news-tickers")
    public ResponseEntity<java.util.List<Map<String, Object>>> getNewsTickers() {
        // Stub: returns empty list until TWS reqNewsBulletins integration is wired
        return ResponseEntity.ok(java.util.List.of());
    }

    @PostMapping("/scan-now")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        boolean mock = mockMarketOpen.get();
        if (!mock && !isMarketHours()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Scan blocked: outside market hours (10:00–22:00 Spain, Mon–Fri).");
            return ResponseEntity.ok(result);
        }
        if (!mock) {
            if (!ibkrService.isConnected() && !accountManager.isConnected()) {
                tradingService.connectAccountManager();
            }
            if (!ibkrService.isConnected() && !accountManager.isConnected()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", false);
                result.put("message", "Please login in TWS with your account before scanning.");
                return ResponseEntity.ok(result);
            }
        }
        
        // Only block a second *manual* scan thread; scheduled scans may run concurrently until preempt below.
        if (scanThread != null && scanThread.isAlive()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Manual scan already in progress");
            return ResponseEntity.ok(result);
        }

        stopScanRequested.set(false);
        isAutoScan.set(false);
        // Preserve scan-activity history across manual starts (same as scheduled scans).
        final boolean isMockScan = mock;
        scanThread = new Thread(() -> {
            preemptExclusiveScanForLive();
            isScanning.set(true);
            // Keep open positions visible: do not clear executedTrades / closedTrades.
            // Preserve Signal rows for tickers that still have a successful execution and are not closed.
            java.util.Set<String> openPositionTickers = new java.util.HashSet<>();
            for (java.util.Map.Entry<String, ExecutedTradeInfo> e : executedTrades.entrySet()) {
                if (e.getValue().success() && !closedTrades.containsKey(e.getKey())) {
                    openPositionTickers.add(e.getKey());
                }
            }
            java.util.List<Signal> preservedSignals = liveSignals.stream()
                    .filter(s -> openPositionTickers.contains(s.ticker()))
                    .toList();
            liveSignals.clear();
            liveSignals.addAll(preservedSignals);
            java.util.Set<String> preservedKeys = preservedSignals.stream()
                    .map(s -> s.ticker().toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            signalFoundAt.keySet().retainAll(preservedKeys);

            tickerScanStartTimes.clear();
            tickerScanEndTimes.clear();
            currentTickerIndex.set(0);
            signalsToday.set(0);

            // Full universe (pre-filter) — exposed as allScanTickers for frontend pre-population
            List<String> fullUniverse = tickerService.getTickerSymbols();
            allScanTickers.set(fullUniverse);

            List<String> allTickers = resolveTickersForLiveScan();
            scannerService.setTickerOverride(allTickers);

            totalTickers.set(allTickers.size());
            scanningTickers.set(allTickers);

            long startTime = System.currentTimeMillis();
            try {
                // Compute and store scan scores BEFORE the scan loop (Pattern A: both entrypoints use setScanScores).
                setScanScores(scanPrioritizationService.computeScores(allTickers));

                // In mock mode: skip data refresh — scan from existing CSVs (simulates 15-min candle close)
                ScanResult result = scannerService.scanAll(true, !isMockScan);

                // Only update signals if stop wasn't requested
                if (!stopScanRequested.get()) {
                    for (Signal sig : result.signals()) {
                        addLiveSignal(sig);
                    }
                    lastScanDuration.set(System.currentTimeMillis() - startTime);

                    // Real scan: same Telegram + bracket path as scheduled MarketScanner (yml + Live UI toggle)
                    if (!isMockScan && !result.signals().isEmpty()) {
                        ZonedDateTime nowSpain = ZonedDateTime.now(SPAIN_TZ);
                        for (Signal sig : result.signals()) {
                            if (stopScanRequested.get()) {
                                break;
                            }
                            marketScanner.processLiveSignalAfterScan(sig, nowSpain);
                        }
                    }

                    // Auto-execute mock signals via TWS when auto-execute is ON
                    if (isMockScan && runtimeAutoExecute && (ibkrService.isConnected() || accountManager.isConnected())) {
                        for (Signal signal : result.signals()) {
                            if (stopScanRequested.get()) break;
                            try {
                                OrderExecutionService.OrderResult orderResult = tradingService.executeManualTrade(signal.ticker(), signal.strategy(), signal.direction(), signal.currentPrice());
                                boolean ok = orderResult != null;
                                String exTime = java.time.LocalTime.now(SPAIN_TZ).format(SPAIN_TIME_FMT);
                                executedTrades.put(signal.ticker(), new ExecutedTradeInfo(signal.ticker(), exTime, ok, ok ? "Auto-executed" : "Failed", ok ? orderResult.parentId() : null, ok ? orderResult.tpOrderId() : null, ok ? orderResult.slOrderId() : null));
                                log.info("Auto-executed mock signal {} {}: {}", signal.ticker(), signal.direction(), ok ? "OK (orderId=" + orderResult.parentId() + ")" : "FAILED");
                            } catch (Exception ex) {
                                log.error("Auto-execute failed for {}: {}", signal.ticker(), ex.getMessage());
                            }
                        }
                    }

                    log.info("Manual scan complete: {} signals in {}ms", result.totalSignals(), result.elapsedMs());
                } else {
                    log.info("Manual scan stopped by user after {}ms", System.currentTimeMillis() - startTime);
                }
            } catch (Exception e) {
                log.error("Manual scan failed: {}", e.getMessage(), e);
            } finally {
                isScanning.set(false);
                isAutoScan.set(false);
                stopScanRequested.set(false);
                lastScanTime.set(System.currentTimeMillis());
                currentTicker.set("");
                scannerService.clearTickerOverride();
                scanThread = null;
            }
        });
        scanThread.start();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Scan started");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/stop-scan")
    public ResponseEntity<Map<String, Object>> stopScan() {
        if (!isScanning.get()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "No scan currently in progress");
            return ResponseEntity.ok(result);
        }

        stopScanRequested.set(true);
        // Interrupt the main scan thread AND all in-flight download threads
        if (scanThread != null) {
            scanThread.interrupt();
        }
        scannerService.stopDownloads();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Scan stop requested");
        return ResponseEntity.ok(result);
    }

    /**
     * Force-stops any ongoing scan and resets all scanning state.
     * Use this when the app is in an inconsistent state (e.g., after restart).
     */
    @PostMapping("/force-stop")
    public ResponseEntity<Map<String, Object>> forceStop() {
        boolean wasScanning = isScanning.get();
        
        // Set stop flag to interrupt any in-progress scanner operations
        stopScanRequested.set(true);
        
        // Interrupt scan thread if running
        if (scanThread != null && scanThread.isAlive()) {
            scanThread.interrupt();
            log.info("Force-stopped scan thread (was scanning: {})", wasScanning);
        }
        // Same as /stop-scan: interrupt in-flight IBKR download threads so scanAll can exit and release the exclusive lock
        scannerService.stopDownloads();

        // Reset all scanning state
        isScanning.set(false);
        stopScanRequested.set(false);
        currentTicker.set("");
        currentTickerIndex.set(0);
        totalTickers.set(0);
        scanActivity.clear();
        
        // Also reset scanner service state
        scannerService.clearStopRequest();
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", wasScanning ? "Force-stopped ongoing scan and reset state" : "Scanning state reset (no scan was running)");
        result.put("wasScanning", wasScanning);
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
        scannerService.setMaxConcurrentScans(count);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("maxConcurrentScans", count);
        result.put("message", "Max concurrent scans set to " + count);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/toggle-extended-hours")
    public ResponseEntity<Map<String, Object>> toggleExtendedHours() {
        boolean newState = extendedHoursEnabled.updateAndGet(v -> !v);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("extendedHours", newState);
        result.put("message", "Extended hours " + (newState ? "enabled" : "disabled"));
        return ResponseEntity.ok(result);
    }

    @PostMapping("/toggle-auto-execute")
    public ResponseEntity<Map<String, Object>> toggleAutoExecute() {
        runtimeAutoExecute = !runtimeAutoExecute;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("autoExecute", runtimeAutoExecute);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/toggle-macro-filter")
    public ResponseEntity<Map<String, Object>> toggleMacroFilter() {
        runtimeMacroFilterEnabled = !runtimeMacroFilterEnabled;
        log.info("Macro filter {}", runtimeMacroFilterEnabled ? "ENABLED" : "DISABLED (manual override)");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("macroFilterEnabled", runtimeMacroFilterEnabled);
        return ResponseEntity.ok(result);
    }

    public boolean isRuntimeMacroFilterEnabled() { return runtimeMacroFilterEnabled; }

    public void setMacroFilterForReplay(boolean enabled) {
        runtimeMacroFilterEnabled = enabled;
        log.info("Macro filter {} (replay session)", enabled ? "ENABLED" : "DISABLED");
    }

    @PostMapping("/set-risk")
    public ResponseEntity<Map<String, Object>> setRisk(@RequestParam double pct) {
        runtimeRiskPct = pct / 100.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("riskPct", pct);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/toggle-scheduler")
    public ResponseEntity<Map<String, Object>> toggleScheduler() {
        boolean newState = !marketScanner.isSchedulerEnabled();
        marketScanner.setSchedulerEnabled(newState);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("schedulerEnabled", newState);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/toggle-mock-market")
    public ResponseEntity<Map<String, Object>> toggleMockMarket() {
        boolean newState = !mockMarketOpen.get();
        mockMarketOpen.set(newState);
        log.info("Mock market open set to: {}", newState);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("mockMarketOpen", newState);
        return ResponseEntity.ok(result);
    }

    private static final List<String> MOCK_STRATEGIES = List.of(
        "C1 Squeeze Breakout", "C2 Trend Continuation", "C3 Bounce",
        "C4 Opening Reversal", "C5 Continuation", "C6 Reversal",
        "P1 Squeeze Breakdown", "P2 Trend Rejection", "P3 Resistance Rejection",
        "P4 Opening Trap", "P5 Continuation", "P6 Reversal"
    );
    private final java.util.concurrent.atomic.AtomicInteger mockSignalIdx = new java.util.concurrent.atomic.AtomicInteger(0);

    @PostMapping("/inject-mock-signal")
    public ResponseEntity<Map<String, Object>> injectMockSignal(
            @RequestParam(defaultValue = "SPY") String ticker,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String direction) {
        // Cycle through strategies each call if not specified
        String strat = (strategy != null && !strategy.isBlank())
                ? strategy
                : MOCK_STRATEGIES.get(mockSignalIdx.getAndIncrement() % MOCK_STRATEGIES.size());

        boolean isCall = direction == null ? strat.startsWith("C") : "CALL".equalsIgnoreCase(direction);
        String dir = isCall ? "CALL" : "PUT";
        
        // Try to get actual ticker price to avoid TWS Error 200 (strike out of range)
        double price = scannerService.getLastKnownPrice(ticker);
        if (price <= 0) {
            price = ticker.equals("SPY") ? 500.0 : 200.0;
        }
        
        double atr = price * 0.012;
        double tp = isCall ? price + atr * 3.0 : price - atr * 3.0;
        double sl = isCall ? price - atr * 2.0 : price + atr * 2.0;

        TradePlan plan = new TradePlan(price, tp, sl, isCall, java.time.LocalTime.of(21, 55), atr);
        Signal mockSignal = new Signal(ticker, strat, dir, price, ZonedDateTime.now(), plan, "mock_signal + hammer");
        addLiveSignal(mockSignal);

        log.info("Injected mock signal: {} {} {} @ ${}", ticker, dir, strat, price);

        boolean telegramViaScanLogic = marketScanner.sendTelegramForScanSignal(mockSignal);

        // Auto-execute injected signal via TWS when auto-execute is ON and TWS is connected
        boolean autoExec = false;
        if (runtimeAutoExecute && (ibkrService.isConnected() || accountManager.isConnected())) {
            try {
                OrderExecutionService.OrderResult orderResult = tradingService.executeManualTrade(ticker, strat, dir, price);
                boolean ok = orderResult != null;
                String exTime = java.time.LocalTime.now(SPAIN_TZ).format(SPAIN_TIME_FMT);
                executedTrades.put(ticker, new ExecutedTradeInfo(ticker, exTime, ok, ok ? "Auto-executed" : "Failed", ok ? orderResult.parentId() : null, ok ? orderResult.tpOrderId() : null, ok ? orderResult.slOrderId() : null));
                autoExec = ok;
                log.info("Auto-executed injected signal {} {}: {}", ticker, dir, ok ? "OK (orderId=" + orderResult.parentId() + ")" : "FAILED");
            } catch (Exception e) {
                log.error("Auto-execute for injected signal {} failed: {}", ticker, e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("ticker", ticker);
        result.put("strategy", strat);
        result.put("direction", dir);
        result.put("entryPrice", price);
        result.put("takeProfit", tp);
        result.put("stopLoss", sl);
        result.put("autoExecuted", autoExec);
        result.put("telegramScanLogicApplied", telegramViaScanLogic);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/set-scan-filter")
    public ResponseEntity<Map<String, Object>> setScanFilter(
            @RequestParam(defaultValue = "") String filter,
            @RequestParam(defaultValue = "HOT") String scope) {
        liveTickerFilter.set(filter.trim());
        liveTickerScope.set(scope.toUpperCase());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("filter", filter.trim());
        result.put("scope", scope.toUpperCase());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/execute-signal")
    public ResponseEntity<Map<String, Object>> executeSignal(
            @RequestParam String ticker,
            @RequestParam String direction,
            @RequestParam double price,
            @RequestParam(defaultValue = "manual") String strategy) {
        return executeTrade(ticker, direction, price, strategy);
    }

    @PostMapping("/execute-trade")
    public ResponseEntity<Map<String, Object>> executeTrade(
            @RequestParam String ticker,
            @RequestParam String direction,
            @RequestParam double price,
            @RequestParam(defaultValue = "manual") String strategy) {
        log.info("🎯 Manual execute request received for {} {} @ ${} (strategy: {})", ticker, direction, price, strategy);

        Signal liveForExecute = findLiveSignalForExecute(ticker, strategy);
        if (liveForExecute != null && isLiveSignalOlderThanMaxAge(liveForExecute)) {
            log.warn("Rejecting execute for {}: signal timestamp {} is older than {} minutes",
                    ticker, liveForExecute.timestamp(), LIVE_SIGNAL_MAX_AGE.toMinutes());
            Map<String, Object> stale = new LinkedHashMap<>();
            stale.put("success", false);
            stale.put("message", "Signal is older than " + LIVE_SIGNAL_MAX_AGE.toMinutes() + " minutes — execution blocked.");
            stale.put("stale", true);
            return ResponseEntity.ok(stale);
        }

        if (!ibkrService.isConnected() && !accountManager.isConnected()) {
            tradingService.connectAccountManager();
        }
        
        if (!ibkrService.isConnected() && !accountManager.isConnected()) {
            log.error("❌ Cannot execute trade: TWS/Gateway not connected.");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "TWS/Gateway not connected. Please login and check connection.");
            return ResponseEntity.ok(result);
        }
        
        try {
            OrderExecutionService.OrderResult orderResult = tradingService.executeManualTrade(ticker, strategy, direction, price);
            boolean ok = orderResult != null;
            String exTime = java.time.LocalTime.now(SPAIN_TZ).format(SPAIN_TIME_FMT);
            
            if (ok) {
                executedTrades.put(ticker, new ExecutedTradeInfo(ticker, exTime, true, "Executed", orderResult.parentId(), orderResult.tpOrderId(), orderResult.slOrderId()));
                log.info("✅ Manual trade sent to TWS: {} {} @ ${} | orderId={}", ticker, direction, price, orderResult.parentId());
            } else {
                executedTrades.put(ticker, new ExecutedTradeInfo(ticker, exTime, false, "Failed", null, null, null));
                log.error("❌ Manual trade failed: No order ID returned from execution service.");
            }
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", ok);
            result.put("message", ok ? "Order sent to TWS successfully." : "Trade execution failed in IBKR service.");
            result.put("executeTime", exTime);
            if (ok) result.put("orderId", orderResult.parentId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            String errorId = UUID.randomUUID().toString();
            log.error("❌ Manual trade exception [{}]: {}", errorId, e.getMessage(), e);
            return ResponseEntity.ok(errorBody("message", "Trade execution failed — see logs.", errorId));
        }
    }

    /**
     * Builds a sanitized error response body. Never leaks exception messages or
     * internal infrastructure details. The {@code errorId} correlates the user-facing
     * response with the server-side log entry where the full stacktrace is recorded.
     */
    private static Map<String, Object> errorBody(String messageKey, String message, String errorId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put(messageKey, message);
        body.put("errorId", errorId);
        return body;
    }

    @PostMapping("/close-trade")
    public ResponseEntity<Map<String, Object>> closeTrade(
            @RequestParam String ticker,
            @RequestParam double price,
            @RequestParam(required = false) Integer tpOrderId,
            @RequestParam(required = false) Integer slOrderId) {
        
        log.info("Close trade requested for {} @ {} | tpId={}, slId={}", ticker, price, tpOrderId, slOrderId);
        
        // If we have TP/SL order IDs, cancel them to trigger OCA group cancellation
        // This effectively closes the position since both conditional orders are cancelled
        if (tpOrderId != null || slOrderId != null) {
            try {
                orderExecutionService.connect(); // Ensure connected
                
                if (tpOrderId != null) {
                    orderExecutionService.cancelOrder(tpOrderId);
                    log.info("Cancelled TP order: {}", tpOrderId);
                }
                if (slOrderId != null) {
                    orderExecutionService.cancelOrder(slOrderId);
                    log.info("Cancelled SL order: {}", slOrderId);
                }
                
                // Mark as closed in our local state
                String closeTime = java.time.LocalTime.now(SPAIN_TZ).format(SPAIN_TIME_FMT);
                closedTrades.put(ticker, new ClosedTradeInfo(ticker, price, closeTime, "CONDITIONAL_CANCEL"));
                
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("ticker", ticker);
                result.put("closePrice", price);
                result.put("closeTime", closeTime);
                result.put("message", "TP/SL orders cancelled - position closed");
                return ResponseEntity.ok(result);
                
            } catch (Exception e) {
                log.error("Failed to cancel TP/SL orders for {}: {}", ticker, e.getMessage());
                // Fall through to simple close
            }
        }
        
        // Fallback: simple local close (original behavior)
        String closeTime = java.time.LocalTime.now(SPAIN_TZ).format(SPAIN_TIME_FMT);
        closedTrades.put(ticker, new ClosedTradeInfo(ticker, price, closeTime, "MANUAL_CLOSE"));
        log.info("Manual close requested for {} at price {}", ticker, price);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("ticker", ticker);
        result.put("closePrice", price);
        result.put("closeTime", closeTime);
        result.put("message", "Trade marked as closed at " + price);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/cancel-trade")
    public ResponseEntity<Map<String, Object>> cancelTrade(
            @RequestParam String ticker,
            @RequestParam int orderId) {
        log.info("Manual cancel requested for {} (orderId={})", ticker, orderId);
        boolean success = tradingService.cancelTrade(orderId);
        if (success) {
            String closeTime = java.time.LocalTime.now(SPAIN_TZ).format(SPAIN_TIME_FMT);
            closedTrades.put(ticker, new ClosedTradeInfo(ticker, 0.0, closeTime, "CANCELLED"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", success ? "Order cancellation sent to TWS" : "Failed to send cancellation");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tws-status")
    public ResponseEntity<Map<String, Object>> getTwsStatus() {
        boolean dataConnected = ibkrService.isConnected();
        boolean accountConnected = accountManager.isConnected();
        boolean execConnected = orderExecutionService.isConnected();
        
        // Comprehensive connection status: green only if the primary account manager is up.
        // Data and Exec are ephemeral and auto-connect on demand, but AccountManager is the heartbeat.
        boolean overallConnected = accountConnected;
        
        if (!overallConnected || !dataConnected || !execConnected) {
            long now = System.currentTimeMillis();
            if (now - lastTwsReconnectAttemptMs.get() >= 5_000) {
                lastTwsReconnectAttemptMs.set(now);
                log.info("🔄 Auto-reconnecting IBKR services (Data={}, Account={}, Exec={})", 
                        dataConnected, accountConnected, execConnected);
                
                if (!accountConnected) tradingService.connectAccountManager();
                if (!dataConnected) try { ibkrService.connect(); } catch (Exception e) { /* TWS may be offline */ }
                if (!execConnected) try { orderExecutionService.connect(); } catch (Exception e) { /* TWS may be offline */ }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("connected", overallConnected);
        result.put("dataConnected", dataConnected);
        result.put("accountConnected", accountConnected);
        result.put("execConnected", execConnected);
        result.put("host", ibkrProperties.host());
        result.put("port", ibkrProperties.port());
        result.put("accountId", accountManager.getAccountId() != null ? accountManager.getAccountId() : ibkrProperties.accountId());
        result.put("autoExecute", runtimeAutoExecute);
        result.put("riskPerTrade", ibkrProperties.riskPerTradePct() * 100 + "%");
        result.put("balance", accountManager.getCurrentBalance());
        result.put("activeTrades", accountManager.getActiveTradeCount());
        return ResponseEntity.ok(result);
    }

    // ===== Public Methods for Internal State Updates =====

    /**
     * Same universe + HOT/ALL scope + comma filter as manual "Scan now" — used by scheduled {@link com.fgiaquinta.optionsquant.service.MarketScanner} too.
     */
    public List<String> resolveTickersForLiveScan() {
        List<String> allTickers = tickerService.getTickerSymbols();
        List<String> hotList = tickerService.getHotTickers();
        java.util.Set<String> hotSet = new java.util.HashSet<>(hotList);

        String scope = liveTickerScope.get();
        if ("HOT".equals(scope)) {
            allTickers = allTickers.stream().filter(hotSet::contains).toList();
        }
        String filter = liveTickerFilter.get();
        if (filter != null && !filter.isBlank()) {
            java.util.Set<String> filterSet = java.util.Arrays.stream(filter.split("[,\\s]+"))
                    .map(String::trim).map(String::toUpperCase)
                    .filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toSet());
            if (!filterSet.isEmpty()) {
                allTickers = allTickers.stream().filter(filterSet::contains).toList();
            }
        }
        return allTickers;
    }

    /** Current live UI scope (HOT / ALL), for logging and diagnostics. */
    public String getLiveTickerScope() {
        return liveTickerScope.get();
    }

    /**
     * Stores an immutable copy of the latest scan-score breakdown together with the
     * wall-clock instant at which the scan started.
     * Called at scan-start by BOTH entrypoints ({@code triggerScan()} and
     * {@link com.fgiaquinta.optionsquant.service.MarketScanner#scanAndExecute()}) before the scan loop runs.
     * Thread-safe: single {@link AtomicReference} write.
     */
    public void setScanScores(Map<String, ScanScoreBreakdown> scores) {
        latestScanScores.set(new ScanScoresResponse(Map.copyOf(scores), Instant.now()));
    }

    /**
     * Returns the latest scan-score envelope ({@code scoresByTicker} + {@code scanStartedAt}).
     * Before the first scan, {@code scoresByTicker} is empty and {@code scanStartedAt} is {@code null}.
     * Thread-safe: lock-free {@link AtomicReference} read.
     */
    @GetMapping("/scan-scores")
    public ResponseEntity<ScanScoresResponse> getScanScores() {
        return ResponseEntity.ok(latestScanScores.get());
    }

    /** Runtime toggle (Live UI) for auto-execute — must be true together with {@code application.yml} auto-execute. */
    public boolean isRuntimeAutoExecute() {
        return runtimeAutoExecute;
    }

    /**
     * If another scan (scheduler, REST, etc.) holds the exclusive IBKR pipeline, stop its downloads and wait
     * for the lock so HOT/live data is not stuck behind a long "remaining" batch.
     */
    private void preemptExclusiveScanForLive() {
        if (!scannerService.isScanAllLockHeld()) {
            return;
        }
        long waitMs = scannerProperties.livePreemptWaitMs();
        log.info("🔴 Live manual scan: freeing IBKR pipeline (stop downloads + stop flag) — waiting up to {}ms for exclusive scan lock",
                waitMs);
        stopScanRequested.set(true);
        scannerService.stopDownloads();
        long deadline = System.currentTimeMillis() + waitMs;
        while (scannerService.isScanAllLockHeld() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stopScanRequested.set(false);
        if (scannerService.isScanAllLockHeld()) {
            log.warn("⚠️ Exclusive scan lock still held after {}ms — this scan may block until it is released", waitMs);
        }
    }

    /** Public for {@link com.fgiaquinta.optionsquant.service.MarketScanner} auto-exec guard. */
    public boolean isLiveSignalOlderThanMaxAge(Signal s) {
        if (s == null || s.timestamp() == null) {
            return false;
        }
        Instant now = (s.replay() && replayClock != null) ? replayClock.getNow().toInstant() : Instant.now();
        return s.timestamp().toInstant().isBefore(now.minus(LIVE_SIGNAL_MAX_AGE));
    }

    /**
     * Matches the row the UI sends on Open: same ticker; strategy "manual" matches any live row for that ticker.
     */
    Signal findLiveSignalForExecute(String ticker, String strategyParam) {
        for (Signal s : liveSignals) {
            if (!s.ticker().equalsIgnoreCase(ticker)) {
                continue;
            }
            if (strategyParam == null || "manual".equalsIgnoreCase(strategyParam)) {
                return s;
            }
            if (s.strategy().equalsIgnoreCase(strategyParam)) {
                return s;
            }
        }
        return null;
    }

    public void setAutoScan(boolean auto) {
        isAutoScan.set(auto);
    }

    public void updateScanningState(boolean scanning, String ticker, int index, int total) {
        // Do not clear scanActivity here: a new scan cycle (scheduler or manual) should keep
        // per-ticker rows so the UI still shows "already scanned" hot tickers when you look back later.
        // Rows are updated in place per ticker; size is capped in addTickerScanActivity.
        isScanning.set(scanning);
        currentTicker.set(ticker);
        currentTickerIndex.set(index);
        totalTickers.set(total);
    }

    private static final int REPLAY_SIGNALS_CAP = 500;

    public void addLiveSignal(Signal signal) {
        if (signal.replay()) {
            // Replay signals are added regardless of wall-clock age; they follow replay time.
            replaySignals.add(signal);
            if (replaySignals.size() > REPLAY_SIGNALS_CAP) {
                replaySignals.remove(0);
            }
            signalFoundAt.put(signal.ticker().toUpperCase(Locale.ROOT), Instant.now());
            appendReplaySignalToJsonl(signal);
            return;
        }

        // Remove old signal for this ticker (prevents duplicates in the grid across scans)
        liveSignals.removeIf(s -> s.ticker().equalsIgnoreCase(signal.ticker()));

        liveSignals.add(signal);
        signalFoundAt.put(signal.ticker().toUpperCase(Locale.ROOT), Instant.now());
        signalsToday.incrementAndGet();
    }

    public List<Signal> getReplaySignals() {
        return List.copyOf(replaySignals);
    }

    public void clearReplaySignals() {
        replaySignals.clear();
    }

    private void appendReplaySignalToJsonl(Signal signal) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(replaySignalDir);
            if (!java.nio.file.Files.exists(dir)) java.nio.file.Files.createDirectories(dir);
            String date = signal.timestamp().toLocalDate().toString();
            String runId = (replayClock != null && replayClock.isActive())
                    ? replayClock.snapshot().runId()
                    : null;
            String filename = (runId != null)
                    ? String.format(java.util.Locale.ROOT, replaySignalsFilePattern, date, runId)
                    : "replay-signals-" + date + ".jsonl";
            java.nio.file.Path file = dir.resolve(filename);
            String tradePlanJson = buildTradePlanJson(signal.tradePlan());
            String line = String.format(java.util.Locale.ROOT,
                    "{\"ticker\":\"%s\",\"strategy\":\"%s\",\"direction\":\"%s\",\"price\":%.4f,\"timestamp\":\"%s\",\"pattern\":\"%s\",\"tradePlan\":%s}%n",
                    signal.ticker(), signal.strategy(), signal.direction(),
                    signal.currentPrice(), signal.timestamp(), signal.candlestickPattern(),
                    tradePlanJson);
            java.nio.file.Files.writeString(file, line,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            log.warn("Failed to persist replay signal to JSONL: {}", e.getMessage());
        }
    }

    private String buildTradePlanJson(TradePlan plan) {
        if (plan == null) return "null";
        return String.format(java.util.Locale.ROOT,
                "{\"entry\":%.4f,\"tp\":%.4f,\"sl\":%.4f}",
                plan.entryPrice, plan.takeProfit, plan.stopLoss);
    }

    public ReplaySummary computeReplaySummary() {
        List<Signal> signals = List.copyOf(replaySignals);
        if (signals.isEmpty()) {
            return new ReplaySummary(0, 0, null, null);
        }
        long uniqueTickerCount = signals.stream()
                .map(Signal::ticker)
                .map(t -> t.toUpperCase(java.util.Locale.ROOT))
                .distinct()
                .count();
        ZonedDateTime first = signals.stream()
                .map(Signal::timestamp)
                .min(ZonedDateTime::compareTo)
                .orElse(null);
        ZonedDateTime last = signals.stream()
                .map(Signal::timestamp)
                .max(ZonedDateTime::compareTo)
                .orElse(null);
        return new ReplaySummary(signals.size(), (int) uniqueTickerCount, first, last);
    }

    /**
     * Removes one live signal row (does not close IBKR positions).
     */
    @DeleteMapping("/signal")
    public ResponseEntity<Map<String, Object>> deleteLiveSignal(@RequestParam String ticker) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (hasOpenExecutedPosition(ticker)) {
            result.put("success", false);
            result.put("message", "Hay una posición abierta para este ticker. Cierra antes de borrar la fila.");
            return ResponseEntity.ok(result);
        }
        boolean removed = liveSignals.removeIf(s -> s.ticker().equalsIgnoreCase(ticker));
        if (removed) {
            signalFoundAt.remove(ticker.toUpperCase(Locale.ROOT));
        }
        result.put("success", removed);
        result.put("ticker", ticker);
        result.put("message", removed ? "Signal removed." : "No matching live signal.");
        return ResponseEntity.ok(result);
    }

    /**
     * Deletes all stale signals (&gt;15m candle age) that are not open positions.
     */
    @PostMapping("/signals/clear-stale")
    public ResponseEntity<Map<String, Object>> clearStaleLiveSignals() {
        List<Signal> toRemove = liveSignals.stream()
                .filter(s -> isLiveSignalOlderThanMaxAge(s))
                .filter(s -> !hasOpenExecutedPosition(s.ticker()))
                .toList();
        for (Signal s : toRemove) {
            liveSignals.remove(s);
            signalFoundAt.remove(s.ticker().toUpperCase(Locale.ROOT));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("removed", toRemove.size());
        return ResponseEntity.ok(result);
    }

    /**
     * Deletes multiple signals by ticker (POST JSON body: ["AAPL","MSFT"]).
     */
    @PostMapping("/signals/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDeleteLiveSignals(@RequestBody List<String> tickers) {
        int removed = 0;
        int skipped = 0;
        if (tickers != null) {
            for (String ticker : tickers) {
                if (ticker == null || ticker.isBlank()) continue;
                if (hasOpenExecutedPosition(ticker)) {
                    skipped++;
                    continue;
                }
                boolean r = liveSignals.removeIf(s -> s.ticker().equalsIgnoreCase(ticker.trim()));
                if (r) {
                    signalFoundAt.remove(ticker.trim().toUpperCase(Locale.ROOT));
                    removed++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("removed", removed);
        result.put("skippedOpenPosition", skipped);
        return ResponseEntity.ok(result);
    }

    private boolean hasOpenExecutedPosition(String ticker) {
        for (Map.Entry<String, ExecutedTradeInfo> e : executedTrades.entrySet()) {
            if (!e.getKey().equalsIgnoreCase(ticker)) continue;
            if (!e.getValue().success()) continue;
            boolean closed = false;
            for (String c : closedTrades.keySet()) {
                if (c.equalsIgnoreCase(ticker)) {
                    closed = true;
                    break;
                }
            }
            return !closed;
        }
        return false;
    }

    // ===== EOD scheduler accessors =====

    /** Unmodifiable view of executed trades for the EOD close scheduler. */
    public java.util.Map<String, ExecutedTradeInfo> getExecutedTrades() {
        return java.util.Collections.unmodifiableMap(executedTrades);
    }

    /** Unmodifiable view of closed trades for the EOD close scheduler. */
    public java.util.Map<String, ClosedTradeInfo> getClosedTrades() {
        return java.util.Collections.unmodifiableMap(closedTrades);
    }

    /** Marks a trade as closed. Used by the EOD close scheduler. */
    public void markClosed(String ticker, ClosedTradeInfo info) {
        closedTrades.put(ticker, info);
    }

    public void updateScanComplete(long durationMs) {
        isScanning.set(false);
        stopScanRequested.set(false);
        lastScanTime.set(System.currentTimeMillis());
        lastScanDuration.set(durationMs);
        currentTicker.set("");
    }

    public boolean isExtendedHoursEnabled() {
        return extendedHoursEnabled.get();
    }

    public boolean isStopRequested() {
        return stopScanRequested.get();
    }

    public void clearStopRequest() {
        stopScanRequested.set(false);
    }

    /**
     * Copy of {@link #scanActivity} ordered by HOT config (then ticker), so the feed matches priority even when parallel scans finish out of order.
     */
    private List<ScanActivity> scanActivitySortedByHotPriority() {
        List<String> hotOrder = tickerService.getHotTickers();
        Map<String, Integer> hotIndex = new HashMap<>();
        for (int i = 0; i < hotOrder.size(); i++) {
            hotIndex.put(hotOrder.get(i).toUpperCase(Locale.ROOT), i);
        }
        List<ScanActivity> copy = new ArrayList<>(scanActivity);
        copy.sort(Comparator
                .comparingInt((ScanActivity a) -> hotIndex.getOrDefault(a.ticker().toUpperCase(Locale.ROOT), 10_000))
                .thenComparing(ScanActivity::ticker));
        return copy;
    }

    /**
     * Called by scanner when a ticker starts being analyzed. Adds or updates a row in the feed.
     */
    void addTickerScanActivity(String payload) {
        String ticker = payload;
        String status = "SCANNING";
        if (payload.contains(":")) {
            String[] parts = payload.split(":");
            ticker = parts[0];
            status = parts[1];
        }

        if (ticker.equals("---")) return; // Skip summary rows

        Instant startInstant = Instant.now();
        String time = startInstant.atZone(SPAIN_TZ).format(SPAIN_TIME_FMT);
        currentTicker.set(ticker);
        tickerScanStartTimes.put(ticker, startInstant);

        String batchLabel = scannerService.getCurrentBatchLabel();
        String detail = status.equals("LOADING") ? "Downloading fresh candles..." :
                       (batchLabel != null && !batchLabel.isEmpty() ? batchLabel : "Analyzing 12 strategies...");

        ScanActivity newEntry = new ScanActivity(time, ticker, status, detail, time, "-", "-");

        // Update existing row if found, otherwise add
        synchronized (scanActivity) {
            boolean updated = false;
            for (int i = 0; i < scanActivity.size(); i++) {
                if (scanActivity.get(i).ticker().equals(ticker)) {
                    scanActivity.set(i, newEntry);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                scanActivity.add(newEntry);
            }
            // BUGFIX: the 200-row cap was evicting early tickers mid-scan on a 503-ticker universe.
            // The frontend then re-stubbed those tickers as '—' because the real rows vanished from the backend.
            // Rule: never evict during an active scan. When idle, keep a generous cap to fit the full universe.
            if (!isScanning.get()) {
                while (scanActivity.size() > 1000) scanActivity.remove(0);
            }
        }
    }

    /**
     * Called by scanner when a ticker scan completes. Updates the existing row to SIGNAL/OK/ERROR.
     */
    void addTickerScanComplete(String ticker, int signalCount) {
        if (ticker.equals("---")) return;

        String time = java.time.LocalTime.now(SPAIN_TZ).format(SPAIN_TIME_FMT);
        String status = signalCount < 0 ? "ERROR" : (signalCount > 0 ? "SIGNAL" : "OK");
        String detail = signalCount < 0 ? "Scan failed" : (signalCount > 0 ? signalCount + " signal(s) found" : "No signals");
        
        Instant startInstant = tickerScanStartTimes.get(ticker);
        String started = startInstant != null ? startInstant.atZone(SPAIN_TZ).format(SPAIN_TIME_FMT) : time;
        String duration = startInstant != null
            ? String.format("%.1fs", java.time.Duration.between(startInstant, Instant.now()).toMillis() / 1000.0)
            : "-";
        
        tickerScanEndTimes.put(ticker, time);
        ScanActivity updatedEntry = new ScanActivity(time, ticker, status, detail, started, time, duration);

        synchronized (scanActivity) {
            for (int i = 0; i < scanActivity.size(); i++) {
                if (scanActivity.get(i).ticker().equals(ticker)) {
                    scanActivity.set(i, updatedEntry);
                    return;
                }
            }
            // Fallback: if not found (rare), just add it
            scanActivity.add(updatedEntry);
        }
    }

    public void updateScanTickerSkipped(String ticker, String reason) {
        synchronized (scanActivity) {
            for (int i = 0; i < scanActivity.size(); i++) {
                ScanActivity row = scanActivity.get(i);
                if (row.ticker().equals(ticker)) {
                    scanActivity.set(i, new ScanActivity(row.time(), ticker, "SKIPPED", "⏭️ " + reason,
                            row.scanStarted(), row.scanEnded(), row.duration()));
                    return;
                }
            }
        }
    }

    private boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(SPAIN_TZ);
        int hour = now.getHour();
        return now.getDayOfWeek().getValue() <= 5 && hour >= 10 && hour < 22;
    }

    // ============================================================
    // Live Replay Mode endpoints
    // ============================================================

    @GetMapping("/account-mode")
    public ResponseEntity<Map<String, Object>> getAccountMode() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", ibkrProperties.isPaperAccount() ? "PAPER" : "LIVE");
        body.put("accountId", ibkrProperties.accountId());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/replay/start")
    public ResponseEntity<Map<String, Object>> startReplay(
            @RequestParam String date,
            @RequestParam(defaultValue = "60") int speed) {
        if (replayService == null) {
            return ResponseEntity.status(404).body(Map.of("error", "replay-disabled"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(date);
            ReplayService.StartResult r = replayService.start(d, speed);
            body.put("success", true);
            body.put("runId", r.runId());
            body.put("virtualNow", r.virtualNow().toString());
            body.put("speed", r.speed());
            return ResponseEntity.ok(body);
        } catch (ReplayService.ReplayRejectedException e) {
            String errorId = UUID.randomUUID().toString();
            log.error("❌ Replay start rejected [{}]: {}", errorId, e.getMessage(), e);
            return ResponseEntity.status(409).body(errorBody("error", "Replay start failed — see logs.", errorId));
        } catch (java.time.format.DateTimeParseException e) {
            body.put("success", false);
            body.put("error", "invalid-date: " + date);
            return ResponseEntity.badRequest().body(body);
        }
    }

    @PostMapping("/replay/stop")
    public ResponseEntity<Map<String, Object>> stopReplay() {
        if (replayService == null) return ResponseEntity.status(404).body(Map.of("error", "replay-disabled"));
        ReplaySummary summary = computeReplaySummary();
        log.info("🎬 Replay complete — signals={} uniqueTickers={} from={} to={}",
                summary.totalSignals(), summary.uniqueTickers(),
                summary.firstSignalAt(), summary.lastSignalAt());
        replayService.stop();
        clearReplaySignals();
        Map<String, Object> summaryMap = new LinkedHashMap<>();
        summaryMap.put("totalSignals", summary.totalSignals());
        summaryMap.put("uniqueTickers", summary.uniqueTickers());
        summaryMap.put("firstSignalAt", summary.firstSignalAt() != null ? summary.firstSignalAt().toString() : null);
        summaryMap.put("lastSignalAt", summary.lastSignalAt() != null ? summary.lastSignalAt().toString() : null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("summary", summaryMap);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/replay/speed")
    public ResponseEntity<Map<String, Object>> setReplaySpeed(@RequestParam int speed) {
        if (replayService == null) return ResponseEntity.status(404).body(Map.of("error", "replay-disabled"));
        try {
            replayService.setSpeed(speed);
            return ResponseEntity.ok(Map.of("success", true, "speed", speed));
        } catch (IllegalArgumentException | IllegalStateException e) {
            String errorId = UUID.randomUUID().toString();
            log.error("❌ Replay speed change failed [{}]: {}", errorId, e.getMessage(), e);
            return ResponseEntity.badRequest().body(errorBody("error", "Replay speed change failed — see logs.", errorId));
        }
    }

    @GetMapping("/replay/status")
    public ResponseEntity<Map<String, Object>> getReplayStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        ReplayClock.State state = replayClock != null ? replayClock.snapshot() : ReplayClock.State.INACTIVE;
        body.put("active", state.active());
        body.put("virtualNow", state.virtualNow() != null ? state.virtualNow().toString() : null);
        body.put("speed", state.speed());
        body.put("runId", state.runId());
        body.put("replaySignalsCount", replaySignals.size());
        return ResponseEntity.ok(body);
    }
}
