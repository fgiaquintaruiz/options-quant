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
        /**
         * API client id for {@link com.fgiaquinta.optionsquant.service.IbkrService} (historical data). MUST be unique per TWS session.
         */
        int historicalDataClientId,
        /**
         * API client id for {@link com.fgiaquinta.optionsquant.service.OrderExecutionService}. MUST differ from data and account ids.
         */
        int orderExecutionClientId,
        /**
         * API client id for {@link com.fgiaquinta.optionsquant.service.AccountManager}.
         */
        int accountManagerClientId
) {
    public IbkrProperties {
        // Default values
        if (tickers == null) tickers = new ArrayList<>();
        if (hotTickers == null) hotTickers = List.of("SPY", "QQQ", "AAPL", "MSFT", "NVDA", "TSLA", "AMZN", "META", "GOOGL", "AMD");
        if (hotTickerCount <= 0) hotTickerCount = 20;  // Default: top 20 tickers by market cap
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
