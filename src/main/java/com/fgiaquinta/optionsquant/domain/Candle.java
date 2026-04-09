package com.fgiaquinta.optionsquant.domain;

import java.time.ZonedDateTime;

/**
 * Represents a single OHLCV candle bar.
 */
public record Candle(
        ZonedDateTime timestamp,
        double open,
        double high,
        double low,
        double close,
        long volume
) {
    @Override
    public String toString() {
        return String.format("%s | O:%.2f H:%.2f L:%.2f C:%.2f V:%d",
                timestamp.toLocalDate(), open, high, low, close, volume);
    }
}
