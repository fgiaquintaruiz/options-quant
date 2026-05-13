package com.fgiaquinta.optionsquant.backtest.dto;

/**
 * Loss aggregate grouped by candlestick pattern.
 */
public record PatternLossDto(
        String pattern,
        long count,
        double avgLoss
) {}
