package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Type-safe IBKR configuration.
 * Only includes fields that are actively used (YAGNI).
 * Registered via @EnableConfigurationProperties in OptionsQuantApplication.
 */
@ConfigurationProperties(prefix = "ibkr")
public record IbkrProperties(
        String host,
        int port,
        int syncTimeout,
        List<String> tickers
) {}
