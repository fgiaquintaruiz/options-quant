package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.ScanResult;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.Duration;
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

    private final StrategyScannerService scannerService;
    private final IbkrProperties ibkrProperties;
    private final TradingService tradingService;
    private final TickerService tickerService;
    private final AccountManager accountManager;
    private final IbkrService ibkrService;
    private final MarketCalendarService marketCalendarService;
    private final com.fgiaquinta.optionsquant.service.MarketScanner marketScanner;

    // Live scanning state
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean isAutoScan = new AtomicBoolean(false);
    private final AtomicBoolean stopScanRequested = new AtomicBoolean(false);
    private final AtomicBoolean mockMarketOpen = new AtomicBoolean(false);
    private final AtomicInteger currentTickerIndex = new AtomicInteger(0);
    private final AtomicInteger totalTickers = new AtomicInteger(0);
    private final AtomicReference<String> currentTicker = new AtomicReference<>("");
    private final AtomicReference<List<String>> scanningTickers = new AtomicReference<>(Collections.emptyList());
    /** Throttle AccountManager reconnect attempts from UI polling */
    private final AtomicLong lastTwsReconnectAttemptMs = new AtomicLong(0);
    private final CopyOnWriteArrayList<Signal> liveSignals = new CopyOnWriteArrayList<>();
    // Manual close requests: ticker → ClosedTradeInfo
    private final java.util.concurrent.ConcurrentHashMap<String, ClosedTradeInfo> closedTrades = new java.util.concurrent.ConcurrentHashMap<>();
    // Manual executions: ticker → ExecutedTradeInfo
    private final java.util.concurrent.ConcurrentHashMap<String, ExecutedTradeInfo> executedTrades = new java.util.concurrent.ConcurrentHashMap<>();
    // Per-ticker scan timing for SCAN STARTED / SCAN FINISHED columns
    private final java.util.concurrent.ConcurrentHashMap<String, String> tickerScanStartTimes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> tickerScanEndTimes = new java.util.concurrent.ConcurrentHashMap<>();
    // Scan activity log: shows what's being scanned in real-time
    private final CopyOnWriteArrayList<ScanActivity> scanActivity = new CopyOnWriteArrayList<>();
    private final AtomicLong lastScanTime = new AtomicLong(0);
    private final AtomicLong lastScanDuration = new AtomicLong(0);
    private final AtomicInteger signalsToday = new AtomicInteger(0);
    private final AtomicBoolean extendedHoursEnabled = new AtomicBoolean(true);
    // Runtime-overridable settings (survive scan but reset on restart)
    private volatile boolean runtimeAutoExecute;
    private volatile double runtimeRiskPct;
    private final AtomicReference<String> liveTickerFilter = new AtomicReference<>("");
    private final AtomicReference<String> liveTickerScope  = new AtomicReference<>("HOT");
    private Thread scanThread = null;

    public record ScanActivity(String time, String ticker, String status, String detail, String scanStarted, String scanEnded, String duration) {}
    public record ClosedTradeInfo(String ticker, double closePrice, String closeTime, String exitReason) {}
    public record ExecutedTradeInfo(String ticker, String executeTime, boolean success, String message, Integer orderId) {}

    public LiveModeController(StrategyScannerService scannerService,
                              IbkrProperties ibkrProperties,
                              TradingService tradingService,
                              TickerService tickerService,
                              AccountManager accountManager,
                              IbkrService ibkrService,
                              MarketCalendarService marketCalendarService,
                              @Lazy com.fgiaquinta.optionsquant.service.MarketScanner marketScanner) {
        this.scannerService = scannerService;
        this.ibkrProperties = ibkrProperties;
        this.tradingService = tradingService;
        this.tickerService = tickerService;
        this.accountManager = accountManager;
        this.ibkrService = ibkrService;
        this.marketCalendarService = marketCalendarService;
        this.marketScanner = marketScanner;
        
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
        status.put("riskPct", Math.round(runtimeRiskPct * 100 * 10.0) / 10.0);
        status.put("liveTickerFilter", liveTickerFilter.get());
        status.put("liveTickerScope", liveTickerScope.get());
        status.put("twsConnected", ibkrService.isConnected() || accountManager.isConnected());

        // Include hot tickers list for UI badge display
        List<String> hotTickers = ibkrProperties.hotTickers() != null
                ? ibkrProperties.hotTickers()
                : List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD");
        status.put("hotTickersList", hotTickers);
        status.put("schedulerEnabled", marketScanner.isSchedulerEnabled());
        status.put("mockMarketOpen", mockMarketOpen.get());

        // Add max concurrent scans setting
        status.put("maxConcurrentScans", scannerService.getMaxConcurrentScans());

        // Add scanner progress info
        status.put("scannerScanned", scannerService.getScannedCount());
        status.put("scannerBatchLabel", scannerService.getCurrentBatchLabel());
        status.put("scannerTotal", scannerService.getTotalToScan());

        ZonedDateTime nowSpain = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        int currentHour = nowSpain.getHour();
        boolean isMarketHours = currentHour >= 10 && currentHour < 22 && nowSpain.getDayOfWeek().getValue() <= 5;
        status.put("marketHours", isMarketHours);
        status.put("currentTime", nowSpain.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")));
        status.put("timezone", "Europe/Madrid");

        return ResponseEntity.ok(status);
    }

    @GetMapping("/signals")
    public ResponseEntity<Map<String, Object>> getSignals() {
        List<Map<String, Object>> signalData = liveSignals.stream().map(s -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ticker", s.ticker());
            map.put("strategy", s.strategy());
            map.put("direction", s.direction());
            map.put("currentPrice", s.currentPrice());
            map.put("timestamp", s.timestamp());
            map.put("tradePlan", s.tradePlan());
            map.put("candlestickPattern", s.candlestickPattern());
            
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
        result.put("activity", scanActivity);
        result.put("isScanning", isScanning.get());
        result.put("batchLabel", scannerService.getCurrentBatchLabel());
        result.put("scanned", scannerService.getScannedCount());
        result.put("total", scannerService.getTotalToScan());
        result.put("lastScanTime", lastScanTime.get());
        result.put("scanStartTimes", tickerScanStartTimes);
        result.put("scanEndTimes", tickerScanEndTimes);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tickers")
    public ResponseEntity<Map<String, Object>> getTickers() {
        List<String> allTickers = ibkrProperties.useCsvTickers()
                ? tickerService.getTickerSymbols()
                : ibkrProperties.tickers();
        List<String> hotTickers = ibkrProperties.hotTickers() != null
                ? ibkrProperties.hotTickers()
                : List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("allTickers", allTickers);
        result.put("hotTickers", hotTickers);
        result.put("total", allTickers.size());
        result.put("scanning", isScanning.get());
        result.put("currentTicker", currentTicker.get());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/scan-now")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        boolean mock = mockMarketOpen.get();
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
        
        if (isScanning.get()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Scan already in progress");
            return ResponseEntity.ok(result);
        }

        stopScanRequested.set(false);
        isAutoScan.set(false);
        scanActivity.clear();
        final boolean isMockScan = mock;
        scanThread = new Thread(() -> {
            isScanning.set(true);
            liveSignals.clear();
            closedTrades.clear();
            executedTrades.clear();
            tickerScanStartTimes.clear();
            tickerScanEndTimes.clear();
            currentTickerIndex.set(0);
            signalsToday.set(0);

            // Build ticker list respecting scope and filter
            List<String> allTickers = ibkrProperties.useCsvTickers()
                    ? tickerService.getTickerSymbols()
                    : ibkrProperties.tickers();
            List<String> hotList = ibkrProperties.hotTickers() != null
                    ? ibkrProperties.hotTickers()
                    : List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD");
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
                if (!filterSet.isEmpty()) allTickers = allTickers.stream().filter(filterSet::contains).toList();
            }
            scannerService.setTickerOverride(allTickers);

            totalTickers.set(allTickers.size());
            scanningTickers.set(allTickers);

            String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            scanActivity.add(new ScanActivity(time, "---", "STARTING", "Scanning " + allTickers.size() + " tickers against 12 strategies", "-", "-", "-"));

            long startTime = System.currentTimeMillis();
            try {
                // In mock mode: skip data refresh — scan from existing CSVs (simulates 15-min candle close)
                ScanResult result = scannerService.scanAll(true, !isMockScan);

                // Only update signals if stop wasn't requested
                if (!stopScanRequested.get()) {
                    liveSignals.addAll(result.signals());
                    signalsToday.addAndGet(result.totalSignals());
                    lastScanDuration.set(System.currentTimeMillis() - startTime);

                    // Auto-execute mock signals via TWS when auto-execute is ON
                    if (isMockScan && runtimeAutoExecute && (ibkrService.isConnected() || accountManager.isConnected())) {
                        for (Signal signal : result.signals()) {
                            if (stopScanRequested.get()) break;
                            try {
                                OrderExecutionService.OrderResult orderResult = tradingService.executeManualTrade(signal.ticker(), signal.strategy(), signal.direction(), signal.currentPrice());
                                boolean ok = orderResult != null;
                                String exTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                                executedTrades.put(signal.ticker(), new ExecutedTradeInfo(signal.ticker(), exTime, ok, ok ? "Auto-executed" : "Failed", ok ? orderResult.parentId() : null));
                                log.info("Auto-executed mock signal {} {}: {}", signal.ticker(), signal.direction(), ok ? "OK (orderId=" + orderResult.parentId() + ")" : "FAILED");
                            } catch (Exception ex) {
                                log.error("Auto-execute failed for {}: {}", signal.ticker(), ex.getMessage());
                            }
                        }
                    }

                    String endTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                    scanActivity.add(new ScanActivity(endTime, "---", "COMPLETE",
                            String.format("%d tickers scanned, %d signals found in %.1fs",
                                    result.tickersScanned(), result.totalSignals(), result.elapsedMs() / 1000.0), "-", "-", "-"));
                    log.info("Manual scan complete: {} signals in {}ms", result.totalSignals(), result.elapsedMs());
                } else {
                    log.info("Manual scan stopped by user after {}ms", System.currentTimeMillis() - startTime);
                }
            } catch (Exception e) {
                log.error("Manual scan failed: {}", e.getMessage(), e);
                String errTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                scanActivity.add(new ScanActivity(errTime, "---", "ERROR", e.getMessage(), "-", "-", "-"));
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
        boolean newState = !extendedHoursEnabled.getAndSet(!extendedHoursEnabled.get());
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
        liveSignals.add(mockSignal);
        signalsToday.incrementAndGet();

        log.info("Injected mock signal: {} {} {} @ ${}", ticker, dir, strat, price);

        // Auto-execute injected signal via TWS when auto-execute is ON and TWS is connected
        boolean autoExec = false;
        if (runtimeAutoExecute && (ibkrService.isConnected() || accountManager.isConnected())) {
            try {
                OrderExecutionService.OrderResult orderResult = tradingService.executeManualTrade(ticker, strat, dir, price);
                boolean ok = orderResult != null;
                String exTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                executedTrades.put(ticker, new ExecutedTradeInfo(ticker, exTime, ok, ok ? "Auto-executed" : "Failed", ok ? orderResult.parentId() : null));
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
            String exTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            
            if (ok) {
                executedTrades.put(ticker, new ExecutedTradeInfo(ticker, exTime, true, "Executed", orderResult.parentId()));
                log.info("✅ Manual trade sent to TWS: {} {} @ ${} | orderId={}", ticker, direction, price, orderResult.parentId());
            } else {
                executedTrades.put(ticker, new ExecutedTradeInfo(ticker, exTime, false, "Failed", null));
                log.error("❌ Manual trade failed: No order ID returned from execution service.");
            }
            
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", ok);
            result.put("message", ok ? "Order sent to TWS successfully." : "Trade execution failed in IBKR service.");
            result.put("executeTime", exTime);
            if (ok) result.put("orderId", orderResult.parentId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("❌ Manual trade exception: {}", e.getMessage(), e);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Internal Error: " + e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    @PostMapping("/close-trade")
    public ResponseEntity<Map<String, Object>> closeTrade(
            @RequestParam String ticker,
            @RequestParam double price) {
        String closeTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
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
            String closeTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            closedTrades.put(ticker, new ClosedTradeInfo(ticker, 0.0, closeTime, "CANCELLED"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("message", success ? "Order cancellation sent to TWS" : "Failed to send cancellation");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tws-status")
    public ResponseEntity<Map<String, Object>> getTwsStatus() {
        if (!ibkrService.isConnected() && !accountManager.isConnected()) {
            long now = System.currentTimeMillis();
            if (now - lastTwsReconnectAttemptMs.get() >= 4_000) {
                lastTwsReconnectAttemptMs.set(now);
                tradingService.connectAccountManager();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        boolean connected = ibkrService.isConnected() || accountManager.isConnected();
        result.put("connected", connected);
        result.put("host", ibkrProperties.host());
        result.put("port", ibkrProperties.port());
        result.put("accountId", ibkrProperties.accountId());
        result.put("autoExecute", ibkrProperties.autoExecute());
        result.put("riskPerTrade", ibkrProperties.riskPerTradePct() * 100 + "%");
        result.put("balance", accountManager.getCurrentBalance());
        result.put("activeTrades", accountManager.getActiveTradeCount());
        return ResponseEntity.ok(result);
    }

    // ===== Public Methods for Internal State Updates =====

    public void setAutoScan(boolean auto) {
        isAutoScan.set(auto);
    }

    public void updateScanningState(boolean scanning, String ticker, int index, int total) {
        // Reset scan-activity feed when a new scan starts (manual or scheduled)
        if (scanning && !isScanning.get()) {
            scanActivity.clear();
        }
        isScanning.set(scanning);
        currentTicker.set(ticker);
        currentTickerIndex.set(index);
        totalTickers.set(total);
    }

    public void addLiveSignal(Signal signal) {
        liveSignals.add(signal);
        signalsToday.incrementAndGet();
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
     * Called by scanner when a ticker starts being analyzed. Adds a row to the feed.
     */
    void addTickerScanActivity(String payload) {
        String ticker = payload;
        String status = "SCANNING";
        if (payload.contains(":")) {
            String[] parts = payload.split(":");
            ticker = parts[0];
            status = parts[1];
        }

        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        currentTicker.set(ticker);
        
        // Track start time for the "Scan Started" column
        if (!ticker.equals("---")) {
            tickerScanStartTimes.put(ticker, time);
        }

        int scanned = scannerService.getScannedCount();
        int total = scannerService.getTotalToScan();
        String batchLabel = scannerService.getCurrentBatchLabel();
        
        String detail;
        if (status.equals("LOADING")) {
            detail = "Downloading fresh candles...";
        } else if (batchLabel != null && !batchLabel.isEmpty()) {
            detail = batchLabel;
        } else {
            detail = "Analyzing 12 strategies...";
        }

        scanActivity.add(new ScanActivity(time, ticker, status, detail, tickerScanStartTimes.getOrDefault(ticker, "-"), "-", "-"));
        // Keep only last 200 entries to prevent memory growth
        while (scanActivity.size() > 200) {
            scanActivity.remove(0);
        }
    }

    /**
     * Called by scanner when a ticker scan completes. Updates the SCANNING row to COMPLETE.
     * @param ticker the ticker that was scanned
     * @param signalCount number of signals found (0 = no signals, -1 = error)
     */
    void addTickerScanComplete(String ticker, int signalCount) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String status = signalCount < 0 ? "ERROR" : (signalCount > 0 ? "SIGNAL" : "OK");
        String detail = signalCount < 0 ? "Scan failed" : (signalCount > 0 ? signalCount + " signal(s) found" : "No signals");
        
        String started = tickerScanStartTimes.getOrDefault(ticker, "-");
        String duration = "-";
        if (!started.equals("-")) {
            try {
                java.time.LocalTime startTime = java.time.LocalTime.parse(started);
                java.time.LocalTime endTime = java.time.LocalTime.parse(time);
                java.time.Duration d = java.time.Duration.between(startTime, endTime);
                duration = String.format("%.1fs", d.toMillis() / 1000.0);
            } catch (Exception e) {
                // Ignore parse errors
            }
        }
        tickerScanEndTimes.put(ticker, time);

        scanActivity.add(new ScanActivity(time, ticker, status, detail, started, time, duration));
        // Keep only last 200 entries to prevent memory growth
        while (scanActivity.size() > 200) {
            scanActivity.remove(0);
        }
    }

    // ===== HTML Dashboard Builder =====

    private String buildLiveDashboardHtml() {
        String currentTime = ZonedDateTime.now(ZoneId.of("Europe/Madrid"))
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        boolean isMarketHours = isMarketHours();
        String statusClass = isMarketHours ? "status-live" : "status-backtest";

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>Live Trading Dashboard</title>\n");
        sb.append("<style>\n");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}\n");
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0d1117;color:#c9d1d9;line-height:1.6}\n");
        sb.append(".container{max-width:1400px;margin:0 auto;padding:20px}\n");
        sb.append(".header{display:flex;justify-content:space-between;align-items:center;padding:20px 0;border-bottom:1px solid #21262d;margin-bottom:20px}\n");
        sb.append(".header h1{font-size:24px;color:#58a6ff}\n");
        sb.append(".status{padding:8px 16px;border-radius:6px;font-size:14px;font-weight:600}\n");
        sb.append(".status-live{background:#238636;color:#fff}\n");
        sb.append(".status-backtest{background:#6e7681;color:#fff}\n");
        sb.append(".nav{display:flex;gap:10px;margin-bottom:20px}\n");
        sb.append(".nav a{padding:10px 20px;background:#21262d;color:#c9d1d9;text-decoration:none;border-radius:6px;font-size:14px}\n");
        sb.append(".nav a.active{background:#58a6ff;color:#fff}\n");
        sb.append(".nav a:hover{background:#30363d}\n");
        sb.append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:20px;margin-bottom:20px}\n");
        sb.append(".card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:20px}\n");
        sb.append(".card h3{font-size:16px;color:#58a6ff;margin-bottom:15px}\n");
        sb.append(".stat{display:flex;justify-content:space-between;padding:10px 0;border-bottom:1px solid #21262d}\n");
        sb.append(".stat:last-child{border-bottom:none}\n");
        sb.append(".stat-label{color:#8b949e}\n");
        sb.append(".stat-value{font-weight:600;color:#58a6ff}\n");
        sb.append(".btn{padding:10px 20px;border:none;border-radius:6px;cursor:pointer;font-size:14px;font-weight:600}\n");
        sb.append(".btn-primary{background:#238636;color:#fff}\n");
        sb.append(".btn-warning{background:#9e6a03;color:#fff}\n");
        sb.append(".ticker-list{max-height:400px;overflow-y:auto;font-size:12px}\n");
        sb.append(".ticker-item{padding:4px 8px;display:flex;justify-content:space-between}\n");
        sb.append(".ticker-item.scanning{background:#388bfd26}\n");
        sb.append(".signals-table{width:100%;border-collapse:collapse;font-size:13px}\n");
        sb.append(".signals-table th,.signals-table td{padding:8px 12px;text-align:left;border-bottom:1px solid #21262d}\n");
        sb.append(".signals-table th{background:#0d1117;color:#8b949e;font-weight:600;position:sticky;top:0}\n");
        sb.append(".signals-table tr:hover{background:#161b22}\n");
        sb.append(".badge{padding:2px 8px;border-radius:12px;font-size:11px;font-weight:600}\n");
        sb.append(".badge-hot{background:#9e6a03;color:#fff}\n");
        sb.append(".progress-bar{height:8px;background:#21262d;border-radius:4px;overflow:hidden;margin:10px 0}\n");
        sb.append(".progress-fill{height:100%;background:linear-gradient(90deg,#58a6ff,#238636);transition:width .3s}\n");
        sb.append(".console-log{background:#0d1117;border:1px solid #30363d;border-radius:6px;padding:10px;font-family:monospace;font-size:12px;max-height:300px;overflow-y:auto}\n");
        sb.append(".log-entry{padding:2px 0}\n");
        sb.append(".log-info{color:#58a6ff}.log-success{color:#3fb950}.log-error{color:#f85149}\n");
        sb.append(".toggle-container{display:flex;gap:10px;align-items:center}\n");
        sb.append(".toggle{position:relative;width:50px;height:26px;background:#30363d;border-radius:13px;cursor:pointer}\n");
        sb.append(".toggle.active{background:#238636}\n");
        sb.append(".toggle::after{content:'';position:absolute;top:3px;left:3px;width:20px;height:20px;background:#fff;border-radius:50%;transition:transform .3s}\n");
        sb.append(".toggle.active::after{transform:translateX(24px)}\n");
        sb.append("</style>\n</head>\n<body>\n");
        sb.append("<div class=\"container\">\n");
        sb.append("<div class=\"header\">\n");
        sb.append("<h1>Live Trading Dashboard</h1>\n");
        sb.append("<div style=\"display:flex;gap:10px;align-items:center\">\n");
        sb.append("<span id=\"clock\" style=\"color:#8b949e;font-size:14px\">").append(currentTime).append("</span>\n");
        sb.append("<span id=\"twsStatusBadge\" style=\"padding:8px 16px;border-radius:6px;font-size:12px;font-weight:600;background:#6e7681;color:#fff\">TWS: Checking...</span>\n");
        sb.append("<span class=\"status ").append(statusClass).append("\">LIVE MODE</span>\n");
        sb.append("</div></div>\n");
        sb.append("<div class=\"nav\">\n");
        sb.append("<a href=\"/live-ui\" class=\"active\">Live Trading</a>\n");
        sb.append("<a href=\"/backtest-ui\">Backtest</a>\n");
        sb.append("<a href=\"/actuator/health\">Health</a>\n");
        sb.append("</div>\n");
        sb.append("<div class=\"grid\">\n");

        // Scanning Status Card
        sb.append("<div class=\"card\"><h3>Scanning Status</h3>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Status:</span><span class=\"stat-value\" id=\"scanStatus\">Idle</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Current Ticker:</span><span class=\"stat-value\" id=\"currentTicker\">-</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Progress:</span><span class=\"stat-value\" id=\"scanProgress\">0/0</span></div>\n");
        sb.append("<div class=\"progress-bar\"><div class=\"progress-fill\" id=\"progressBar\" style=\"width:0%\"></div></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Last Scan:</span><span class=\"stat-value\" id=\"lastScan\">-</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Duration:</span><span class=\"stat-value\" id=\"scanDuration\">-</span></div>\n");
        sb.append("<button class=\"btn btn-primary\" id=\"startScanBtn\" onclick=\"startScan()\" style=\"width:100%;margin-top:10px\">▶ Start Scan</button>\n");
        sb.append("<button class=\"btn btn-warning\" id=\"stopScanBtn\" onclick=\"stopScan()\" style=\"width:100%;margin-top:10px;display:none\">⏹ Stop Scan</button>\n");
        sb.append("</div>\n");

        // Signals Card
        sb.append("<div class=\"card\"><h3>Signals Today</h3>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Signals:</span><span class=\"stat-value\" id=\"signalsToday\" style=\"font-size:24px\">0</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Auto-Execute:</span><div class=\"toggle-container\"><div class=\"toggle\" id=\"autoExecToggle\"></div><span id=\"autoExecStatus\" style=\"font-size:12px\">OFF</span></div></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Extended Hours:</span><div class=\"toggle-container\"><div class=\"toggle\" id=\"extHoursToggle\" onclick=\"toggleExtendedHours()\"></div><span id=\"extHoursStatus\" style=\"font-size:12px\">OFF</span></div></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Balance:</span><span class=\"stat-value\" id=\"balance\">$0</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Active Trades:</span><span class=\"stat-value\" id=\"activeTrades\">0</span></div>\n");
        sb.append("</div>\n");

        // TWS Card
        sb.append("<div class=\"card\"><h3>TWS Connection</h3>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Host:</span><span class=\"stat-value\" id=\"twsHost\">-</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Port:</span><span class=\"stat-value\" id=\"twsPort\">-</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Account:</span><span class=\"stat-value\" id=\"twsAccount\">-</span></div>\n");
        sb.append("<div class=\"stat\"><span class=\"stat-label\">Risk/Trade:</span><span class=\"stat-value\" id=\"twsRisk\">-</span></div>\n");
        sb.append("<button class=\"btn btn-warning\" onclick=\"checkTws()\" style=\"width:100%;margin-top:10px\">Check Connection</button>\n");
        sb.append("</div>\n");

        // Tickers Card
        sb.append("<div class=\"card\"><h3>Tickers Queue</h3>\n");
        sb.append("<div class=\"ticker-list\" id=\"tickerList\"><div style=\"text-align:center;padding:20px;color:#8b949e\">Loading...</div></div>\n");
        sb.append("</div>\n");
        sb.append("</div>\n");

        // Signals Table
        sb.append("<div class=\"card\"><h3>Live Signals Feed</h3>\n");
        sb.append("<div style=\"overflow-x:auto;max-height:500px;overflow-y:auto\">\n");
        sb.append("<table class=\"signals-table\"><thead><tr>\n");
        sb.append("<th>Time</th><th>Ticker</th><th>Strategy</th><th>Dir</th><th>Price</th><th>TP</th><th>SL</th><th>Pattern</th><th>Status</th>\n");
        sb.append("</tr></thead><tbody id=\"signalsBody\">\n");
        sb.append("<tr><td colspan=\"9\" style=\"text-align:center;padding:20px;color:#8b949e\" id=\"signalsPlaceholder\">Waiting for scan...</td></tr>\n");
        sb.append("</tbody></table></div></div>\n");

        // Console Log
        sb.append("<div class=\"card\" style=\"margin-top:20px\"><h3>Console Log</h3>\n");
        sb.append("<div class=\"console-log\" id=\"consoleLog\"></div></div>\n");
        sb.append("</div>\n");

        // JavaScript
        sb.append("<script>\n");
        sb.append("setInterval(function(){document.getElementById('clock').textContent=new Date().toLocaleTimeString()},1000);\n");
        sb.append("async function loadStatus(){try{var r=await fetch('/live-ui/status');var d=await r.json();updateUI(d)}catch(e){console.error(e)}}\n");
        sb.append("async function loadTickers(){try{var r=await fetch('/live-ui/tickers');var d=await r.json();renderTickers(d)}catch(e){console.error(e)}}\n");
        sb.append("async function loadTws(){try{var r=await fetch('/live-ui/tws-status');var d=await r.json();\n");
        sb.append("document.getElementById('twsHost').textContent=d.host||'-';\n");
        sb.append("document.getElementById('twsPort').textContent=d.port||'-';\n");
        sb.append("document.getElementById('twsAccount').textContent=d.accountId||'-';\n");
        sb.append("document.getElementById('twsRisk').textContent=d.riskPerTrade||'-';\n");
        sb.append("var bal=d.balance||0;\n");
        sb.append("document.getElementById('balance').textContent=bal>0?'$'+bal.toLocaleString():'N/A (no TWS)';\n");
        sb.append("document.getElementById('activeTrades').textContent=d.activeTrades||0}catch(e){}}\n");
        sb.append("async function loadSignals(){try{var r=await fetch('/live-ui/signals');var d=await r.json();renderSignals(d)}catch(e){}}\n");
        sb.append("function updateUI(s){\n");
        sb.append("var statusText=s.isScanning?'Scanning':'Idle';\n");
        sb.append("if(s.stopScanRequested){statusText='Stopping...'}\n");
        sb.append("document.getElementById('scanStatus').textContent=statusText;\n");
        sb.append("var batchLabel=s.scannerBatchLabel||'';\n");
        sb.append("var curTicker=s.scannerBatchLabel||s.currentTicker||'-';\n");
        sb.append("document.getElementById('currentTicker').textContent=curTicker;\n");
        sb.append("var scanned=s.scannerScanned||0;var total=s.scannerTotal||s.totalTickers||0;\n");
        sb.append("document.getElementById('scanProgress').textContent=scanned+'/'+total;\n");
        sb.append("document.getElementById('progressBar').style.width=(total>0?(scanned/total*100):0)+'%';\n");
        sb.append("document.getElementById('signalsToday').textContent=s.signalsToday||0;\n");
        sb.append("document.getElementById('autoExecStatus').textContent=s.autoExecute?'ON':'OFF';\n");
        sb.append("document.getElementById('autoExecToggle').classList.toggle('active',s.autoExecute);\n");
        sb.append("document.getElementById('extHoursStatus').textContent=s.extendedHoursEnabled?'ON':'OFF';\n");
        sb.append("document.getElementById('extHoursToggle').classList.toggle('active',s.extendedHoursEnabled);\n");
        sb.append("document.getElementById('startScanBtn').style.display=(s.isScanning||s.stopScanRequested)?'none':'block';\n");
        sb.append("document.getElementById('stopScanBtn').style.display=s.isScanning?'block':'none';\n");
        sb.append("if(s.lastScanTime){document.getElementById('lastScan').textContent=new Date(s.lastScanTime).toLocaleTimeString()}\n");
        sb.append("if(s.lastScanDuration){document.getElementById('scanDuration').textContent=(s.lastScanDuration/1000).toFixed(1)+'s'}}\n");
        sb.append("function renderTickers(d){\n");
        sb.append("var c=document.getElementById('tickerList');var hot=d.hotTickers||[];var all=d.allTickers||[];\n");
        sb.append("var h='';var currentTicker=d.currentTicker||'';var scanning=d.scanning;\n");
        sb.append("hot.forEach(function(t){var isScan=scanning&&t===currentTicker;\n");
        sb.append("h+='<div class=\"ticker-item'+(isScan?' scanning':'')+'\"><span><span class=\"badge badge-hot\">HOT</span> '+t+'</span>'+(isScan?'<span>Scanning...</span>':'')+'</div>'});\n");
        sb.append("all.forEach(function(t,i){if(hot.indexOf(t)<0){var isScan=scanning&&t===currentTicker;\n");
        sb.append("h+='<div class=\"ticker-item'+(isScan?' scanning':'')+'\"><span>'+t+'</span>'+(isScan?'<span>Scanning...</span>':'')+'</div>'}});\n");
        sb.append("c.innerHTML=h}\n");
        sb.append("function renderSignals(d){\n");
        sb.append("var body=document.getElementById('signalsBody');var signals=d.signals||[];var count=d.count||0;\n");
        sb.append("var statusEl=document.getElementById('scanStatus');var isScanning=statusEl&&statusEl.textContent==='Scanning';\n");
        sb.append("var curTicker=document.getElementById('currentTicker');var tickerText=curTicker?curTicker.textContent:'-';\n");
        sb.append("if(count===0&&!isScanning){body.innerHTML='<tr><td colspan=\\'9\\' style=\\'text-align:center;padding:20px;color:#8b949e\\'>No signals today. Scan completed with no matches.</td></tr>'}\n");
        sb.append("else if(count===0&&isScanning){body.innerHTML='<tr><td colspan=\\'9\\' style=\\'text-align:center;padding:20px;color:#58a6ff\\'>Scanning: '+tickerText+'</td></tr>'}\n");
        sb.append("else if(count>0){var h='';signals.forEach(function(s){\n");
        sb.append("var time=s.timestamp?s.timestamp.substring(11,16):'-';\n");
        sb.append("var tp=s.tradePlan&&s.tradePlan.takeProfit?s.tradePlan.takeProfit:'-';\n");
        sb.append("var sl=s.tradePlan&&s.tradePlan.stopLoss?s.tradePlan.stopLoss:'-';\n");
        sb.append("var pattern=s.candlestickPattern||'-';\n");
        sb.append("var dirClass=s.direction==='CALL'?'positive':'negative';\n");
        sb.append("h+='<tr><td>'+time+'</td><td><strong>'+s.ticker+'</strong></td><td>'+s.strategy+'</td>';\n");
        sb.append("h+='<td class=\\''+dirClass+'\\'>'+s.direction+'</td><td>$'+s.currentPrice+'</td>';\n");
        sb.append("h+='<td>$'+tp+'</td><td>$'+sl+'</td><td>'+pattern+'</td><td><span class=\\'badge badge-hot\\'>NEW</span></td></tr>'});\n");
        sb.append("body.innerHTML=h}\n");
        sb.append("document.getElementById('signalsToday').textContent=d.signalsToday||count}\n");
        sb.append("async function startScan(){try{var r=await fetch('/live-ui/scan-now',{method:'POST'});var d=await r.json();\n");
        sb.append("addLog(d.success?'Scan started':'Error: '+d.message,d.success?'success':'error');loadStatus()}catch(e){addLog('Error: '+e.message,'error')}}\n");
        sb.append("async function stopScan(){try{var r=await fetch('/live-ui/stop-scan',{method:'POST'});var d=await r.json();\n");
        sb.append("addLog(d.success?'Scan stopped':'Error: '+d.message,d.success?'success':'error');loadStatus()}catch(e){addLog('Error: '+e.message,'error')}}\n");
        sb.append("async function toggleExtendedHours(){try{var r=await fetch('/live-ui/toggle-extended-hours',{method:'POST'});var d=await r.json();\n");
        sb.append("document.getElementById('extHoursStatus').textContent=d.extendedHours?'ON':'OFF';\n");
        sb.append("document.getElementById('extHoursToggle').classList.toggle('active',d.extendedHours);\n");
        sb.append("addLog('Extended hours: '+(d.extendedHours?'ON':'OFF'),'info')}catch(e){addLog('Error: '+e.message,'error')}}\n");
        sb.append("async function checkTws(){addLog('Checking TWS...','info');try{var r=await fetch('/live-ui/tws-status');var d=await r.json();\n");
        sb.append("addLog('TWS: '+d.host+':'+d.port,'success')}catch(e){addLog('TWS error: '+e.message,'error')}}\n");
        sb.append("function addLog(msg,type){type=type||'info';var log=document.getElementById('consoleLog');\n");
        sb.append("var t=new Date().toLocaleTimeString();var e=document.createElement('div');\n");
        sb.append("e.className='log-entry log-'+type;e.textContent='['+t+'] '+msg;\n");
        sb.append("log.insertBefore(e,log.firstChild);while(log.children.length>100)log.removeChild(log.lastChild)}\n");
        sb.append("loadStatus();loadTickers();loadTws();loadSignals();\n");
        sb.append("setInterval(loadStatus,500);setInterval(loadTickers,5000);setInterval(loadTws,10000);setInterval(loadSignals,3000);\n");
        sb.append("addLog('Live Trading Dashboard loaded','success');\n");
        sb.append("</script>\n</body>\n</html>");

        return sb.toString();
    }

    private boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        int hour = now.getHour();
        return now.getDayOfWeek().getValue() <= 5 && hour >= 10 && hour < 22;
    }
}