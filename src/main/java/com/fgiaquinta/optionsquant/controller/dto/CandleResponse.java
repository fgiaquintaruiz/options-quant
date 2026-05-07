package com.fgiaquinta.optionsquant.controller.dto;

/**
 * Wire-safe projection of a single OHLCV candle bar returned by the historical API.
 * Separates the internal {@link com.fgiaquinta.optionsquant.domain.Candle} record from the HTTP contract.
 */
public record CandleResponse(
        String ticker,
        String date,
        double open,
        double high,
        double low,
        double close,
        long volume
) {}
