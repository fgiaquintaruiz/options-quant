package com.fgiaquinta.optionsquant.backtest.domain;

import java.time.ZonedDateTime;

/**
 * Records a complete trade (entry + exit) for reporting.
 */
public record TradeRecord(
        String ticker,
        String strategy,
        String direction,
        int quantity,
        double entryPrice,
        ZonedDateTime entryTime,
        double exitPrice,
        ZonedDateTime exitTime,
        String exitReason,
        double grossPnl,
        double commission,
        double slippage,
        double netPnl,
        double maxDrawdown,
        double maxRunup
) {}
