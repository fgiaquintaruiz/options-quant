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
        List<String> hotTickers,  // Priority tickers to scan first
        int hotTickerCount,        // How many CSV tickers to promote as hot (if hotTickers is empty)
        // When non-empty, replaces CSV as full universe (ALL scope). Empty = all symbols from data/tickers.csv
        List<String> universeTickers,
        int historicalDataClientId,   // IbkrService / historical data client id
        int orderExecutionClientId,   // Order execution client id
        int accountManagerClientId    // AccountManager client id
) {
    public IbkrProperties {
        // Default values
        if (tickers == null) tickers = new ArrayList<>();
        if (hotTickers == null) hotTickers = List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD");
        if (hotTickerCount <= 0) hotTickerCount = 20;  // Default: top 20 tickers by market cap
        if (universeTickers == null) universeTickers = List.of();
        if (historicalDataClientId <= 0) historicalDataClientId = 1;
        if (orderExecutionClientId <= 0) orderExecutionClientId = 2;
        if (accountManagerClientId <= 0) accountManagerClientId = 999;
    }
    
    /**
     * Gets tickers from YAML config (for backwards compatibility).
     */
    public List<String> getYamlTickers() {
        return tickers != null ? tickers : new ArrayList<>();
    }
}
