package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scanner throughput and prioritization (live strategy scan).
 * <p>
 * {@code concurrent-mode=AUTO} leaves logical CPUs for the OS/IDE; {@code FIXED} uses {@code fixed-max-concurrent}.
 * {@code prioritization-mode=HYBRID} orders non-hot tickers by fundamentals + learned memory; {@code NATURAL} keeps CSV order.
 */
@ConfigurationProperties(prefix = "scanner")
public record ScannerProperties(
        ConcurrentMode concurrentMode,
        int fixedMaxConcurrent,
        int autoReserveLogicalCpus,
        int autoMinConcurrent,
        int autoMaxConcurrentCap,
        PrioritizationMode prioritizationMode,
        double hybridFundamentalWeight,
        double hybridMemoryWeight
) {
    public ScannerProperties {
        if (concurrentMode == null) {
            concurrentMode = ConcurrentMode.AUTO;
        }
        if (fixedMaxConcurrent < 1) {
            fixedMaxConcurrent = 4;
        }
        if (autoReserveLogicalCpus < 0) {
            autoReserveLogicalCpus = 4;
        }
        if (autoMinConcurrent < 1) {
            autoMinConcurrent = 1;
        }
        if (autoMaxConcurrentCap < 1) {
            autoMaxConcurrentCap = 6;
        }
        if (prioritizationMode == null) {
            prioritizationMode = PrioritizationMode.HYBRID;
        }
        if (hybridFundamentalWeight <= 0) {
            hybridFundamentalWeight = 0.65;
        }
        if (hybridMemoryWeight <= 0) {
            hybridMemoryWeight = 0.35;
        }
    }

    public enum ConcurrentMode {
        AUTO,
        FIXED
    }

    public enum PrioritizationMode {
        /** Fundamentals + ticker memory blend; priority list tier first. */
        HYBRID,
        /** Order as loaded from CSV / config (legacy). */
        NATURAL
    }
}
