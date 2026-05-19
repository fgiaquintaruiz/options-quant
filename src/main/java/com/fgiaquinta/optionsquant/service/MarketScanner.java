package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.ScanResult;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatically scans market and executes trades during market hours.
 * 
 * Synchronized with 15-minute candle closures:
 * - Spain timezone (CEST, UTC+2)
 * - Pre-market: 10:00 - 15:30 Spain time
 * - Market hours: 15:30 - 22:00 Spain time  
 * - Runs 2 seconds after each 15-min candle closes
 * 
 * Cron: "2 0/15 10-21 * * MON-FRI"
 * - Second: 2 (2 seconds after the minute)
 * - Minute: 0, 15, 30, 45 (every 15 min)
 * - Hour: 10-21 (10:00 to 21:45 Spain time)
 * - Day of week: MON-FRI
 */
@Slf4j
@Component
@EnableScheduling
public class MarketScanner {

    private final StrategyScannerService scannerService;
    private final IbkrProperties ibkrProperties;
    private final com.fgiaquinta.optionsquant.service.OrderExecutionService orderExecutionService;
    private final MacroEnvironmentFilter macroFilter;
    private final TelegramService telegramService;
    private final TrailingStopMonitor trailingStopMonitor;
    private final com.fgiaquinta.optionsquant.controller.LiveModeController liveModeController;
    private final MarketCalendarService marketCalendar;
    private final ScannerProperties scannerProperties;
    private final ScanPrioritizationService scanPrioritizationService;

    // Live-replay-mode hooks — optional; null when feature not wired in the context.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReplayClock replayClock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ReplayOrderGate replayOrderGate;

    private static final ZoneId SPAIN_TZ = ZoneId.of("Europe/Madrid");

    /** When false, scheduled scans are skipped (user can toggle from UI). */
    private final AtomicBoolean schedulerEnabled = new AtomicBoolean(true);

    public boolean isSchedulerEnabled() { return schedulerEnabled.get(); }
    public void setSchedulerEnabled(boolean enabled) { schedulerEnabled.set(enabled); }

    public MarketScanner(StrategyScannerService scannerService,
                         IbkrProperties ibkrProperties,
                         com.fgiaquinta.optionsquant.service.OrderExecutionService orderExecutionService,
                         MacroEnvironmentFilter macroFilter,
                         TelegramService telegramService,
                         TrailingStopMonitor trailingStopMonitor,
                         com.fgiaquinta.optionsquant.controller.LiveModeController liveModeController,
                         MarketCalendarService marketCalendar,
                         ScannerProperties scannerProperties,
                         ScanPrioritizationService scanPrioritizationService) {
        this.scannerService = scannerService;
        this.ibkrProperties = ibkrProperties;
        this.orderExecutionService = orderExecutionService;
        this.macroFilter = macroFilter;
        this.telegramService = telegramService;
        this.trailingStopMonitor = trailingStopMonitor;
        this.liveModeController = liveModeController;
        this.marketCalendar = marketCalendar;
        this.scannerProperties = scannerProperties;
        this.scanPrioritizationService = scanPrioritizationService;
        log.info("🤖 MarketScanner initialized - Spain timezone, 15-min synchronized");
        log.info("   Auto-execute: {}", ibkrProperties.autoExecute());
        log.info("   Macro filter: ENABLED (multi-factor: SPY 50-SMA + short-term momentum)");
        log.info("   Trailing stop monitor: ENABLED ({} min max hold, SMA20 trailing)", 90);
        log.info("   Order execution: REGULAR MARKET HOURS ONLY (9:30 AM - 4:00 PM ET)");
        log.info("   Exclusive scan lock: scheduler try {}ms, live preempt {}ms",
                scannerProperties.exclusiveScanSchedulerLockWaitMs(), scannerProperties.livePreemptWaitMs());
    }

    /**
     * Downloads fresh candle delta as soon as the app starts.
     * Runs on a background thread so it doesn't block application ready state.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ZonedDateTime nowSpain = ZonedDateTime.now(SPAIN_TZ);
        
        // Only download during market hours or pre-market
        int currentHour = nowSpain.getHour();
        if (currentHour >= 10 && currentHour < 22 && nowSpain.getDayOfWeek().getValue() <= 5) {
            log.info("🔄 Market hours detected - downloading fresh candle delta in background...");
            log.info("   Duration depends on live scope (HOT vs ALL) and ticker count...");
            log.info("   App is ready; scan will complete in background.");
            
            // Run on background thread so the app reaches "ready" state immediately
            CompletableFuture.runAsync(() -> {
                if (!schedulerEnabled.get()) {
                    log.info("⏸️ Scheduler disabled — skipping startup scan");
                    return;
                }
                List<String> tickers = liveModeController.resolveTickersForLiveScan();
                log.info("🔄 Startup scan: scope={} → {} tickers (same list as Live manual scan)",
                        liveModeController.getLiveTickerScope(), tickers.size());

                liveModeController.setAutoScan(true);
                liveModeController.updateScanningState(true, "Startup scan...", 0, tickers.size());
                long startTime = System.currentTimeMillis();
                scannerService.setTickerOverride(tickers);
                try {
                    long schedWait = scannerProperties.exclusiveScanSchedulerLockWaitMs();
                    ScanResult result = scannerService.scanAll(true, true, false, schedWait);
                    if (result.lockSkipped()) {
                        log.warn("⏸️ Startup scan skipped — exclusive IBKR scan lock busy after {}ms (another scan running)", schedWait);
                        return;
                    }
                    long elapsed = System.currentTimeMillis() - startTime;
                    for (Signal signal : result.signals()) {
                        liveModeController.addLiveSignal(signal);
                    }
                    log.info("✅ Startup scan complete: {} tickers, {} signals in {}ms",
                            result.tickersScanned(), result.totalSignals(), elapsed);
                } catch (Exception e) {
                    log.warn("⚠️ Startup scan failed", e);
                } finally {
                    scannerService.clearTickerOverride();
                    // Always reset scanning state so Stop Scan can clear it
                    liveModeController.updateScanComplete(System.currentTimeMillis() - startTime);
                    liveModeController.setAutoScan(false);
                }
            });
        } else {
            log.info("⏸️ Outside market hours - skipping startup delta download");
            log.info("   MarketScanner will activate at 10:00 Spain time on next weekday");
        }
    }

    /**
     * Runs 2 seconds after every 15-minute candle closes during market hours.
     */
    @Scheduled(cron = "2 0/15 10-21 * * MON-FRI", zone = "Europe/Madrid")
    public void scanAndExecute() {
        boolean replayActive = replayClock != null && replayClock.isActive();
        ZonedDateTime nowSpain = replayActive
                ? replayClock.getNow().withZoneSameInstant(SPAIN_TZ)
                : ZonedDateTime.now(SPAIN_TZ);

        // Market-hours gate applies to both live and replay — replay must not generate
        // signals outside the real trading window even when running at virtual time.
        if (!marketCalendar.isRegularMarketHours(nowSpain)) {
            log.debug("⏸️ Outside market hours ({} Spain) - skipping", nowSpain.toLocalTime());
            return;
        }

        // Skip if scheduler disabled or stop was requested
        if (!schedulerEnabled.get()) {
            log.debug("⏸️ Scheduler disabled — skipping scheduled scan");
            return;
        }
        if (liveModeController.isStopRequested()) {
            log.info("⏹️ Stop scan requested - skipping scheduled scan");
            liveModeController.clearStopRequest();
            return;
        }

        log.info("\n🔍 === MARKET SCAN === {} (Spain) ===", nowSpain.toLocalTime());

        long scanStartTime = System.currentTimeMillis();

        try {
            List<String> tickers = liveModeController.resolveTickersForLiveScan();
            log.info("Scheduled scan: scope={} → {} tickers: {}",
                    liveModeController.getLiveTickerScope(), tickers.size(), tickers);

            liveModeController.setAutoScan(true);
            liveModeController.updateScanningState(true, "Starting...", 0, tickers.size());

            // Compute and store scan scores BEFORE the scan loop (Pattern A dual-entrypoint).
            liveModeController.setScanScores(scanPrioritizationService.computeScores(tickers));

            scannerService.setTickerOverride(tickers);
            long schedWait = scannerProperties.exclusiveScanSchedulerLockWaitMs();
            ScanResult result = scannerService.scanAll(true, true, false, schedWait);
            if (result.lockSkipped()) {
                log.warn("⏸️ Scheduled scan skipped — exclusive IBKR scan lock busy after {}ms (e.g. live manual scan or /api/strategies/scan)", schedWait);
                return;
            }

            for (Signal signal : result.signals()) {
                liveModeController.addLiveSignal(signal);
            }
            
            log.info("\n📈 === SCAN SUMMARY ===");
            log.info("  Time: {} (Spain)", nowSpain.toLocalTime());
            log.info("  Tickers scanned: {}", result.tickersScanned());
            log.info("  Signals found: {}", result.totalSignals());
            log.info("  Scan duration: {}ms", result.elapsedMs());
            
            // Log each signal and execute if enabled
            if (!result.signals().isEmpty()) {
                log.info("\n🎯 SIGNALS DETECTED:");
                for (Signal signal : result.signals()) {
                    processLiveSignalAfterScan(signal, nowSpain);
                }
            } else {
                log.info("  No signals detected this scan");
            }
            
            log.info("======================\n");

        } catch (Exception e) {
            log.error("❌ MarketScanner error", e);
        } finally {
            scannerService.clearTickerOverride();
            // Always reset scanning state so Stop Scan works
            liveModeController.updateScanComplete(System.currentTimeMillis() - scanStartTime);
            liveModeController.setAutoScan(false);
        }
    }

    /**
     * Logs one signal, sends Telegram, then optionally places a bracket order when both
     * {@link IbkrProperties#autoExecute()} and {@link com.fgiaquinta.optionsquant.controller.LiveModeController#isRuntimeAutoExecute()} are true
     * (same guards as the historical inline scheduled-scan path). Used by scheduled scans and manual "Scan now".
     */
    public void processLiveSignalAfterScan(Signal signal, ZonedDateTime nowSpain) {
        log.info("  {} {} @ ${} - {} at {}",
                signal.ticker(), signal.direction(),
                signal.currentPrice(), signal.strategy(),
                signal.timestamp());

        sendTelegramForScanSignal(signal);

        if (!ibkrProperties.autoExecute()) {
            log.info("    ℹ️ Auto-execute disabled in application.yml");
            return;
        }
        if (!liveModeController.isRuntimeAutoExecute()) {
            log.info("    ℹ️ Auto-execute toggled OFF in Live UI — use Execute on the signal or enable the toggle");
            return;
        }

        if (signal.tradePlan() == null) {
            log.warn("    ⚠️ Auto-execute skipped: No TradePlan available");
            return;
        }
        boolean isCall = signal.direction().equals("CALL");
        if (liveModeController.isRuntimeMacroFilterEnabled() && !macroFilter.isMacroFavorable(isCall)) {
            String skipReason = String.format("macro: %s + %s (%s)",
                    macroFilter.getRegime(), macroFilter.getMomentum(), macroFilter.getAnalysisString());
            log.warn("⏭️ Skipping {} {} — {}", signal.ticker(), signal.direction(), skipReason);
            liveModeController.updateScanTickerSkipped(signal.ticker(), skipReason);
            return;
        }

        if (liveModeController.isLiveSignalOlderThanMaxAge(signal)) {
            log.warn("⏭️ Skipping {} {} — signal candle timestamp older than 30 minutes (stale data)",
                    signal.ticker(), signal.direction());
            return;
        }

        if (!signal.replay() && !marketCalendar.isRegularMarketHours(nowSpain)) {
            log.info("    ⏸️ Skipping execution - outside regular market hours (9:30 AM - 4:00 PM ET)");
            log.info("       Signal remains in the grid for manual execution when the market opens");
            return;
        }

        // Replay rate limit — high speeds can fire too many brackets at once.
        if (signal.replay() && replayOrderGate != null && !replayOrderGate.tryConsume()) {
            log.warn("    ⏭️ REPLAY rate limit: skipping auto-exec for {} {} (cap {} orders/min)",
                    signal.ticker(), signal.direction(), replayOrderGate.count());
            return;
        }

        try {
            log.info("    🚀 AUTO-EXECUTING bracket order...");

            OrderExecutionService.OrderResult orderResult =
                    orderExecutionService.placeOptionBracket(
                            signal.ticker(),
                            isCall,
                            ibkrProperties.defaultQty(),
                            signal.tradePlan(),
                            signal.strategy()
                    );

            if (orderResult != null) {
                log.info("    ✅ ORDER PLACED: Parent ID={}, Strike={}, Expiration={}, Right={}",
                        orderResult.parentId(), orderResult.strike(),
                        orderResult.expiration(), orderResult.right());

                trailingStopMonitor.registerPosition(
                        signal.ticker(),
                        isCall,
                        signal.currentPrice(),
                        orderResult.slOrderId()
                );
            } else {
                log.error("    ❌ ORDER FAILED: Check logs for IBKR errors");
            }
        } catch (Exception e) {
            log.error("    ❌ EXECUTION ERROR: {}", signal.ticker(), e);
        }
    }

    /** Same notion as manual execute for freshness: valid plan, candle time, not stale (mock signals still notify for smoke tests). */
    private boolean shouldNotifyTelegram(Signal signal) {
        if (signal.tradePlan() == null || signal.timestamp() == null) {
            return false;
        }
        return !liveModeController.isLiveSignalOlderThanMaxAge(signal);
    }

    /**
     * Sends Telegram using the exact same rules as {@link #scanAndExecute()} (fresh candle, TradePlan).
     * Public so {@link com.fgiaquinta.optionsquant.controller.LiveModeController#injectMockSignal} can smoke-test the bot.
     *
     * @return {@code true} if notification path was applied ({@link TelegramService} may still no-op when disabled)
     */
    public boolean sendTelegramForScanSignal(Signal signal) {
        if (signal.tradePlan() == null) {
            log.warn("    ⚠️ No TradePlan available - cannot execute order");
            return false;
        }
        TradePlan plan = signal.tradePlan();
        log.info("    TP: ${} | SL: ${} | Entry: ${}",
                plan.takeProfit, plan.stopLoss, plan.entryPrice);

        if (!shouldNotifyTelegram(signal)) {
            log.info("    ℹ️ Telegram skipped (stale candle >30m or missing timestamp)");
            return false;
        }
        if (telegramService == null) {
            return false;
        }
        if (ibkrProperties.autoExecute()) {
            telegramService.sendAutoExecuteSignal(
                    signal.ticker(),
                    signal.strategy(),
                    signal.direction(),
                    signal.currentPrice(),
                    plan.takeProfit,
                    plan.stopLoss
            );
        } else {
            String secureOrderId = telegramService.generateSecureOrderId(
                    signal.ticker(),
                    signal.strategy(),
                    signal.direction(),
                    signal.currentPrice()
            );
            telegramService.sendSignal(
                    signal.ticker(),
                    signal.strategy(),
                    signal.direction(),
                    signal.currentPrice(),
                    plan.takeProfit,
                    plan.stopLoss,
                    secureOrderId
            );
        }
        return true;
    }
}
