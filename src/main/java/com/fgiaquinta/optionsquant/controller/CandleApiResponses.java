package com.fgiaquinta.optionsquant.controller;

/**
 * Typed response records for the Candle API.
 * Replaces Map<String, Object> for type safety and OpenAPI generation.
 */
public final class CandleApiResponses {

    private CandleApiResponses() {}

    public record DownloadResponse(
            String ticker,
            String timeframe,
            int candles,
            boolean saved
    ) {}

    public record DownloadAllResponse(
            String ticker,
            java.util.Map<String, Integer> timeframes
    ) {}

    public record DownloadAllTickersResponse(
            int totalTickers,
            int successfulTickers,
            int failedTickers,
            java.util.Map<String, CandleApiResponses.DownloadAllResponse> results,
            java.util.Map<String, String> errors
    ) {}

    public record StatusResponse(
            boolean connected
    ) {}

    public record ErrorResponse(
            String error,
            long timestamp
    ) {
        public ErrorResponse(String error) {
            this(error, System.currentTimeMillis());
        }
    }
}
