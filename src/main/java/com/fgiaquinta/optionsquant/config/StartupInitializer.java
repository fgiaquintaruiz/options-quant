package com.fgiaquinta.optionsquant.config;

import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.service.TickerService;
import com.fgiaquinta.optionsquant.service.TradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Initializes services on application startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupInitializer {

    private final TickerService tickerService;
    private final TradingService tradingService;
    private final BacktestEngine backtestEngine;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("🚀 Initializing trading services...");

        // Clear any previous backtest state so the UI starts fresh
        try {
            backtestEngine.clearCheckpoint();
            log.info("✅ Previous backtest checkpoint cleared");
        } catch (Exception e) {
            log.warn("⚠️ Failed to clear backtest checkpoint: {}", e.getMessage());
        }

        // Load tickers from CSV
        try {
            tickerService.loadTickers();
            log.info("✅ Ticker service initialized with {} tickers", tickerService.getLoadedCount());
        } catch (Exception e) {
            log.error("❌ Failed to initialize ticker service: {}", e.getMessage());
        }

        // Connect AccountManager to IBKR for balance sync
        try {
            tradingService.connectAccountManager();
            log.info("✅ AccountManager connected to IBKR for balance sync");
        } catch (Exception e) {
            log.warn("⚠️ AccountManager connection failed (TWS may not be running): {}", e.getMessage());
        }

        log.info("✅ All services initialized");
    }
}
