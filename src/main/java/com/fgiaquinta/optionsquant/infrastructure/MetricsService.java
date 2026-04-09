package com.fgiaquinta.optionsquant.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;

/**
 * Centralized metrics service using Micrometer.
 * Provides convenient methods for timing and counting operations.
 */
@Component
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry registry;

    private static final String IBKR_METRIC = "ibkr";
    private static final String CSV_METRIC = "csv";
    private static final String HTTP_METRIC = "http";

    // ===== IBKR Metrics =====

    public <T> T timeIbkrCall(String operation, Callable<T> action) throws Exception {
        return Timer.builder(IBKR_METRIC + ".operation")
                .tag("operation", operation)
                .register(registry)
                .recordCallable(action);
    }

    public void timeIbkrCall(String operation, Runnable action) {
        Timer.builder(IBKR_METRIC + ".operation")
                .tag("operation", operation)
                .register(registry)
                .record(action);
    }

    public void incrementIbkrError(String errorType) {
        Counter.builder(IBKR_METRIC + ".errors")
                .tag("type", errorType)
                .register(registry)
                .increment();
    }

    // ===== CSV Metrics =====

    public void timeCsvCall(String operation, Runnable action) {
        Timer.builder(CSV_METRIC + ".operation")
                .tag("operation", operation)
                .register(registry)
                .record(action);
    }

    public void incrementCsvOperation(String type) {
        Counter.builder(CSV_METRIC + ".operations")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    // ===== HTTP Metrics =====

    public void incrementHttpCall(String endpoint, String method) {
        Counter.builder(HTTP_METRIC + ".requests")
                .tag("endpoint", endpoint)
                .tag("method", method)
                .register(registry)
                .increment();
    }
}
