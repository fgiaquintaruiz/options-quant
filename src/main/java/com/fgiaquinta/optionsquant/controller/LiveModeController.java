package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
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
    private final OrderExecutionService orderExecutionService;
    private final MarketCalendarService marketCalendarService;
    private final com.fgiaquinta.optionsquant.service.MarketScanner marketScanner;
    private final ScannerProperties scannerProperties;

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
    public record ExecutedTradeInfo(String ticker, String executeTime, boolean success, String message, Integer orderId, Integer tpOrderId, Integer slOrderId) {}

    public LiveModeController(StrategyScannerService scannerService,
                              IbkrProperties ibkrProperties,
                              TradingService tradingService,
                              TickerService tickerService,
                              AccountManager accountManager,
                              IbkrService ibkrService,
                              OrderExecutionService orderExecutionService,
                              MarketCalendarService marketCalendarService,
                              @Lazy com.fgiaquinta.optionsquant.service.MarketScanner marketScanner,
                              ScannerProperties scannerProperties) {
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
        status.put("twsConnected", accountManager.isConnected());

        // Include hot tickers list for UI badge display
        List<String> hotTickers = ibkrProperties.hotTickers() != null
                ? ibkrProperties.hotTickers()
                : List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD");
        status.put("hotTickersList", hotTickers);
        status.put("schedulerEnabled", marketScanner.isSchedulerEnabled());
        status.put("mockMarketOpen", mockMarketOpen.get());

        // Add max concurrent scans setting
        status.put("maxConcurrentScans", scannerService.getMaxConcurrentScans());
        status.put("scannerConcurrentMode", scannerProperties.concurrentMode().name());
        status.put("scannerPrioritizationMode", scannerProperties.prioritizationMode().name());
        status.put("scannerHybridFundamentalWeight", scannerProperties.hybridFundamentalWeight());
        status.put("scannerHybridMemoryWeight", scannerProperties.hybridMemoryWeight());

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
                String closeTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
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

        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        currentTicker.set(ticker);
        tickerScanStartTimes.put(ticker, time);

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
            while (scanActivity.size() > 200) scanActivity.remove(0);
        }
    }

    /**
     * Called by scanner when a ticker scan completes. Updates the existing row to SIGNAL/OK/ERROR.
     */
    void addTickerScanComplete(String ticker, int signalCount) {
        if (ticker.equals("---")) return;

        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        String status = signalCount < 0 ? "ERROR" : (signalCount > 0 ? "SIGNAL" : "OK");
        String detail = signalCount < 0 ? "Scan failed" : (signalCount > 0 ? signalCount + " signal(s) found" : "No signals");
        
        String started = tickerScanStartTimes.getOrDefault(ticker, time);
        String duration = "-";
        try {
            java.time.LocalTime startTime = java.time.LocalTime.parse(started);
            java.time.LocalTime endTime = java.time.LocalTime.parse(time);
            duration = String.format("%.1fs", java.time.Duration.between(startTime, endTime).toMillis() / 1000.0);
        } catch (Exception e) { /* ignore */ }
        
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

    private boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        int hour = now.getHour();
        return now.getDayOfWeek().getValue() <= 5 && hour >= 10 && hour < 22;
    }
}
