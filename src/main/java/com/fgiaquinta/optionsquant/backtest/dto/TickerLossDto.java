package com.fgiaquinta.optionsquant.backtest.dto;

/**
 * Loss aggregate grouped by ticker symbol.
 */
public record TickerLossDto(
        String ticker,
        long count,
        double totalLoss
) {}
