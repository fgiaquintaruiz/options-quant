package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Type-safe IBKR configuration.
 */
@ConfigurationProperties(prefix = "ibkr", ignoreUnknownFields = true)
public record IbkrProperties(
        String host,
        int port,
        int syncTimeout,
        List<String> tickers,
        boolean autoExecute,
        String accountId,
        int defaultQty,
        double riskPerTradePct,
        int hotTickerCount,        // How many CSV tickers to promote as hot (top N by market cap)
        int historicalDataClientId,   // IbkrService / historical data client id
        int orderExecutionClientId,   // Order execution client id
        int accountManagerClientId    // AccountManager client id
) {
    public IbkrProperties {
        // Default values
        if (tickers == null) tickers = new ArrayList<>();
        if (hotTickerCount <= 0) hotTickerCount = 20;  // Default: top 20 tickers by market cap
        if (historicalDataClientId <= 0) historicalDataClientId = 1;
        if (orderExecutionClientId <= 0) orderExecutionClientId = 2;
        if (accountManagerClientId <= 0) accountManagerClientId = 999;
    }
    
    /** IBKR paper accounts start with "DU" (e.g. DUN598126). Live accounts start with "U" or other. */
    public boolean isPaperAccount() {
        return accountId != null && accountId.strip().startsWith("DU");
    }
}
