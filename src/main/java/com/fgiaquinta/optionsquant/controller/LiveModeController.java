package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.ScanResult;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.time.ZoneId;
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

    // Live scanning state
    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private final AtomicBoolean stopScanRequested = new AtomicBoolean(false);
    private final AtomicInteger currentTickerIndex = new AtomicInteger(0);
    private final AtomicInteger totalTickers = new AtomicInteger(0);
    private final AtomicReference<String> currentTicker = new AtomicReference<>("");
    private final AtomicReference<List<String>> scanningTickers = new AtomicReference<>(Collections.emptyList());
    private final CopyOnWriteArrayList<Signal> liveSignals = new CopyOnWriteArrayList<>();
    private final AtomicLong lastScanTime = new AtomicLong(0);
    private final AtomicLong lastScanDuration = new AtomicLong(0);
    private final AtomicInteger signalsToday = new AtomicInteger(0);
    private final AtomicBoolean extendedHoursEnabled = new AtomicBoolean(true);
    private Thread scanThread = null;

    public LiveModeController(StrategyScannerService scannerService,
                              IbkrProperties ibkrProperties,
                              TradingService tradingService,
                              TickerService tickerService,
                              AccountManager accountManager) {
        this.scannerService = scannerService;
        this.ibkrProperties = ibkrProperties;
        this.tradingService = tradingService;
        this.tickerService = tickerService;
        this.accountManager = accountManager;
        log.info("LiveModeController initialized");
    }

    @GetMapping
    public String dashboard() {
        return buildLiveDashboardHtml();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("isScanning", isScanning.get());
        status.put("stopScanRequested", stopScanRequested.get());
        status.put("currentTicker", currentTicker.get());
        status.put("currentTickerIndex", currentTickerIndex.get());
        status.put("totalTickers", totalTickers.get());
        status.put("lastScanTime", lastScanTime.get());
        status.put("lastScanDuration", lastScanDuration.get());
        status.put("signalsToday", signalsToday.get());
        status.put("extendedHoursEnabled", extendedHoursEnabled.get());
        status.put("autoExecute", ibkrProperties.autoExecute());

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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signals", liveSignals);
        result.put("count", liveSignals.size());
        result.put("signalsToday", signalsToday.get());
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
        if (isScanning.get()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "Scan already in progress");
            return ResponseEntity.ok(result);
        }

        stopScanRequested.set(false);
        scanThread = new Thread(() -> {
            isScanning.set(true);
            liveSignals.clear();
            currentTickerIndex.set(0);
            signalsToday.set(0);

            List<String> allTickers = ibkrProperties.useCsvTickers()
                    ? tickerService.getTickerSymbols()
                    : ibkrProperties.tickers();
            totalTickers.set(allTickers.size());
            scanningTickers.set(allTickers);

            long startTime = System.currentTimeMillis();
            try {
                ScanResult result = scannerService.scanAll(true, true);

                // Only update signals if stop wasn't requested
                if (!stopScanRequested.get()) {
                    liveSignals.addAll(result.signals());
                    signalsToday.addAndGet(result.totalSignals());
                    lastScanDuration.set(System.currentTimeMillis() - startTime);
                    log.info("Manual scan complete: {} signals in {}ms", result.totalSignals(), result.elapsedMs());
                } else {
                    log.info("Manual scan stopped by user after {}ms", System.currentTimeMillis() - startTime);
                }
            } catch (Exception e) {
                log.error("Manual scan failed: {}", e.getMessage(), e);
            } finally {
                isScanning.set(false);
                stopScanRequested.set(false);
                lastScanTime.set(System.currentTimeMillis());
                currentTicker.set("");
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
        if (scanThread != null) {
            scanThread.interrupt();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("message", "Scan stop requested");
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

    @PostMapping("/execute-trade")
    public ResponseEntity<Map<String, Object>> executeTrade(
            @RequestParam String ticker,
            @RequestParam String direction,
            @RequestParam double price,
            @RequestParam(defaultValue = "manual") String strategy) {
        try {
            boolean success = tradingService.executeManualTrade(ticker, strategy, direction, price);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", success);
            result.put("message", success ? "Trade executed" : "Trade failed");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.ok(result);
        }
    }

    @GetMapping("/tws-status")
    public ResponseEntity<Map<String, Object>> getTwsStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
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

    public void updateScanningState(boolean scanning, String ticker, int index, int total) {
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
        sb.append("setInterval(loadStatus,2000);setInterval(loadTickers,5000);setInterval(loadTws,10000);setInterval(loadSignals,3000);\n");
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
