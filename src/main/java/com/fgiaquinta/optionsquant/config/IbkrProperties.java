package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Type-safe IBKR configuration.
 * Supports both YAML-list and CSV-based ticker loading.
 */
@ConfigurationProperties(prefix = "ibkr")
public record IbkrProperties(
        String host,
        int port,
        int syncTimeout,
        List<String> tickers,
        boolean autoExecute,
        String accountId,
        int defaultQty,
        double riskPerTradePct,
        boolean useCsvTickers,
        List<String> hotTickers  // Priority tickers to scan first
) {
    public IbkrProperties {
        // Default values
        if (tickers == null) tickers = new ArrayList<>();
        if (hotTickers == null) hotTickers = List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD");
    }
    
    /**
     * Gets tickers from YAML config (for backwards compatibility).
     */
    public List<String> getYamlTickers() {
        return tickers != null ? tickers : new ArrayList<>();
    }
}
