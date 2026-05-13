package com.fgiaquinta.optionsquant.backtest.dto;

/**
 * Loss aggregate grouped by hour of day (e.g. "09", "10", "14").
 */
public record HourlyLossDto(
        String hour,
        long count,
        double avgLoss
) {}
