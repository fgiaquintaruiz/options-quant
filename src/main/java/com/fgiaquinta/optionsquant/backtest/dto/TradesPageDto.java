package com.fgiaquinta.optionsquant.backtest.dto;

import java.util.List;

/**
 * Paginated result for the trades endpoint.
 */
public record TradesPageDto(
        List<TradeRecordDto> trades,
        long total
) {}
