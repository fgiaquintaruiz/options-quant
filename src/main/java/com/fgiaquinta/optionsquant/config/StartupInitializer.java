package com.fgiaquinta.optionsquant.config;

import com.fgiaquinta.optionsquant.service.TickerService;
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

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("🚀 Initializing trading services...");
        
        // Load tickers from CSV
        try {
            tickerService.loadTickers();
            log.info("✅ Ticker service initialized with {} tickers", tickerService.getLoadedCount());
        } catch (Exception e) {
            log.error("❌ Failed to initialize ticker service: {}", e.getMessage());
        }
        
        log.info("✅ All services initialized");
    }
}
