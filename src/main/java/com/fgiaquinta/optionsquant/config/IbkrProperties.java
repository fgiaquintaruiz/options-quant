package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Type-safe IBKR configuration.
 */
@ConfigurationProperties(prefix = "ibkr")
public record IbkrProperties(
        String host,
        int port,
        int syncTimeout,
        List<String> tickers,
        boolean autoExecute,
        String accountId,
        int defaultQty
) {
    public IbkrProperties {
        if (autoExecute == false) {
            // Default is false for safety - paper trading first
        }
    }
}
