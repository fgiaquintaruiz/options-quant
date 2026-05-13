package com.fgiaquinta.optionsquant.backtest.dto;

import java.util.List;

/**
 * Multi-dimensional loss analysis for a backtest run.
 */
public record LossesAnalysisDto(
        List<HourlyLossDto> byHour,
        List<PatternLossDto> byPattern,
        List<TickerLossDto> byTicker,
        List<TradeRecordDto> worstTrades
) {}
