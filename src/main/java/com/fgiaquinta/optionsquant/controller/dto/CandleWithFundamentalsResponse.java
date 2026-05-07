package com.fgiaquinta.optionsquant.controller.dto;

import java.util.List;

/**
 * Response envelope for the GET /api/v1/historical/{ticker}/with-fundamentals endpoint.
 * Combines ticker fundamentals with the requested OHLCV candle range.
 */
public record CandleWithFundamentalsResponse(
        TickerInfoDto ticker,
        List<CandleResponse> candles
) {}
