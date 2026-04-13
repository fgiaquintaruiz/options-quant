package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
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

    public MarketScanner(StrategyScannerService scannerService,
                         IbkrProperties ibkrProperties,
                         com.fgiaquinta.optionsquant.service.OrderExecutionService orderExecutionService,
                         MacroEnvironmentFilter macroFilter,
                         TelegramService telegramService,
                         TrailingStopMonitor trailingStopMonitor) {
        this.scannerService = scannerService;
        this.ibkrProperties = ibkrProperties;
        this.orderExecutionService = orderExecutionService;
        this.macroFilter = macroFilter;
        this.telegramService = telegramService;
        this.trailingStopMonitor = trailingStopMonitor;
        log.info("🤖 MarketScanner initialized - Spain timezone, 15-min synchronized");
        log.info("   Auto-execute: {}", ibkrProperties.autoExecute());
        log.info("   Macro filter: ENABLED (multi-factor: SPY 50-SMA + short-term momentum)");
        log.info("   Trailing stop monitor: ENABLED ({} min max hold, SMA20 trailing)", 90);
    }

    /**
     * Downloads fresh candle delta as soon as the app starts.
     * This ensures data is ready before the first scheduled scan.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        ZonedDateTime nowSpain = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        
        // Only download during market hours or pre-market
        int currentHour = nowSpain.getHour();
        if (currentHour >= 10 && currentHour < 22 && nowSpain.getDayOfWeek().getValue() <= 5) {
            log.info("🔄 Market hours detected - downloading fresh candle delta...");
            log.info("   This may take a few minutes for 512 tickers...");
            
            try {
                long startTime = System.currentTimeMillis();
                // Include trade plans so we see TP/SL in logs
                ScanResult result = scannerService.scanAll(true, true);
                long elapsed = System.currentTimeMillis() - startTime;
                
                log.info("✅ Startup delta download complete!");
                log.info("   Tickers refreshed: {}", result.tickersScanned());
                log.info("   Signals found: {}", result.totalSignals());
                log.info("   Duration: {}ms ({} min)", elapsed, String.format("%.1f", elapsed / 60000.0));
                log.info("   Next scan: at next 15-min boundary");
            } catch (Exception e) {
                log.warn("⚠️ Startup delta download failed: {} (will retry at next scan)", e.getMessage());
            }
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
        ZonedDateTime nowSpain = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        
        // Skip if outside market hours
        int currentHour = nowSpain.getHour();
        if (currentHour < 10 || currentHour >= 22) {
            log.debug("⏸️ Outside market hours ({}:{} Spain) - skipping", currentHour, nowSpain.getMinute());
            return;
        }

        // Skip weekend (double check - cron already handles this)
        if (nowSpain.getDayOfWeek().getValue() > 5) {
            return;
        }

        log.info("\n🔍 === MARKET SCAN === {} (Spain) ===", 
                nowSpain.toLocalTime());
        
        try {
            // Scan all tickers with trade plans, auto-refresh data
            ScanResult result = scannerService.scanAll(true, true);
            
            log.info("\n📈 === SCAN SUMMARY ===");
            log.info("  Time: {} (Spain)", nowSpain.toLocalTime());
            log.info("  Tickers scanned: {}", result.tickersScanned());
            log.info("  Signals found: {}", result.totalSignals());
            log.info("  Scan duration: {}ms", result.elapsedMs());
            
            // Log each signal and execute if enabled
            if (!result.signals().isEmpty()) {
                log.info("\n🎯 SIGNALS DETECTED:");
                for (Signal signal : result.signals()) {
                    log.info("  {} {} @ ${} - {} at {}",
                            signal.ticker(), signal.direction(),
                            signal.currentPrice(), signal.strategy(),
                            signal.timestamp());

                    // Send Telegram notification
                    if (signal.tradePlan() != null) {
                        TradePlan plan = signal.tradePlan();
                        log.info("    TP: ${} | SL: ${} | Entry: ${}",
                                plan.takeProfit, plan.stopLoss, plan.entryPrice);

                        // Send signal to Telegram
                        // If auto-execute is ON: send info-only notification
                        // If auto-execute is OFF: send signal with "Execute" button
                        if (telegramService != null) {
                            if (ibkrProperties.autoExecute()) {
                                // Auto-execute enabled: send info-only message
                                telegramService.sendAutoExecuteSignal(
                                        signal.ticker(),
                                        signal.strategy(),
                                        signal.direction(),
                                        signal.currentPrice(),
                                        plan.takeProfit,
                                        plan.stopLoss
                                );
                            } else {
                                // Auto-execute disabled: send signal with confirmation button
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
                        }
                    } else {
                        log.warn("    ⚠️ No TradePlan available - cannot execute order");
                    }

                    // Auto-execute if enabled and TradePlan is available
                    if (ibkrProperties.autoExecute()) {
                        if (signal.tradePlan() != null) {
                            // Check macro environment before executing
                            boolean isCall = signal.direction().equals("CALL");
                            if (!macroFilter.isMacroFavorable(isCall)) {
                                log.warn("⏭️ Skipping {} {} - macro unfavorable", signal.ticker(), signal.direction());
                                continue;
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

                                    // Register for trailing stop monitoring
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
                                log.error("    ❌ EXECUTION ERROR: {} - {}", signal.ticker(), e.getMessage());
                            }
                        } else {
                            log.warn("    ⚠️ Auto-execute skipped: No TradePlan available");
                        }
                    } else {
                        log.info("    ℹ️ Auto-execute disabled in application.yml");
                    }
                }
            } else {
                log.info("  No signals detected this scan");
            }
            
            log.info("======================\n");
            
        } catch (Exception e) {
            log.error("❌ MarketScanner error: {}", e.getMessage(), e);
        }
    }
}
