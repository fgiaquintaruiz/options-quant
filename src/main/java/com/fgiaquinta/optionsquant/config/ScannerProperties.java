package com.fgiaquinta.optionsquant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scanner throughput and prioritization (live strategy scan).
 * <p>
 * {@code concurrent-mode=AUTO} leaves logical CPUs for the OS/IDE; {@code FIXED} uses {@code fixed-max-concurrent}.
 * {@code prioritization-mode=HYBRID} orders non-hot tickers by fundamentals + learned memory; {@code NATURAL} keeps CSV order.
 * <p>
 * {@code exclusive-scan-scheduler-lock-wait-ms}: scheduled/startup scan tryLock timeout; {@code live-preempt-wait-ms}: Live manual scan wait for lock after preempt.
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
        double hybridMemoryWeight,
        long exclusiveScanSchedulerLockWaitMs,
        long livePreemptWaitMs
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
        if (exclusiveScanSchedulerLockWaitMs <= 0) {
            exclusiveScanSchedulerLockWaitMs = 5000L;
        }
        if (livePreemptWaitMs <= 0) {
            livePreemptWaitMs = 60_000L;
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
