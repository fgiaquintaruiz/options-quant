package com.fgiaquinta.optionsquant.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Drives periodic {@code reqPositions()} refresh on the IBKR account stream.
 *
 * <p>The scheduler fires every {@code ibkr.positions-poll-seconds} ms (default 10 000 ms).
 * If {@link AccountManager} is not connected the tick is silently skipped (WARN logged).
 *
 * <p>The property is validated at startup ({@link #validatePollInterval()}): values outside
 * [5 000, 60 000] ms cause an {@link IllegalStateException} so misconfiguration is caught before
 * the application accepts traffic.
 */
@Slf4j
@Component
public class PositionPollingScheduler {

    private final AccountManager accountManager;
    private final long pollIntervalMs;

    public PositionPollingScheduler(
            AccountManager accountManager,
            @Value("${ibkr.positions-poll-seconds:10000}") long pollIntervalMs) {
        this.accountManager = accountManager;
        this.pollIntervalMs = pollIntervalMs;
    }

    /**
     * Validates that the configured poll interval is within the acceptable range.
     * Called once at application startup, before the first scheduled tick.
     *
     * @throws IllegalStateException if the interval is outside [5 000, 60 000] ms
     */
    @PostConstruct
    void validatePollInterval() {
        if (pollIntervalMs < 5_000 || pollIntervalMs > 60_000) {
            throw new IllegalStateException(
                    "ibkr.positions-poll-seconds must be between 5000 and 60000 (got: " + pollIntervalMs + ")");
        }
    }

    /**
     * Triggers an IBKR position refresh.
     * Skips the tick and logs WARN when the account stream is disconnected.
     */
    @Scheduled(fixedRateString = "${ibkr.positions-poll-seconds:10000}", timeUnit = TimeUnit.MILLISECONDS)
    public void pollPositions() {
        if (!accountManager.isConnected()) {
            log.warn("AccountManager not connected, skipping position poll");
            return;
        }
        accountManager.reqPositions();
    }
}
