package com.fgiaquinta.optionsquant.backtest.dto;

/**
 * Individual trade record returned by the trades paged endpoint.
 */
public record TradeRecordDto(
        long id,
        String runId,
        String ticker,
        String strategy,
        String timeframe,
        String signalDate,
        String signalType,
        double entryPrice,
        double exitPrice,
        double pnl,
        int win,
        String pattern
) {}
