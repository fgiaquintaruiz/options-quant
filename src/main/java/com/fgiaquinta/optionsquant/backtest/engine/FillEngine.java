package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.FillResult;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;

import java.time.ZonedDateTime;

/**
 * Simulates order fills during backtesting.
 * Different implementations can model slippage, commission, spread, etc.
 */
public interface FillEngine {

    /**
     * Simulates filling an entry order.
     */
    FillResult fillEntry(String ticker, String direction, int quantity, TradePlan plan, ZonedDateTime timestamp);

    /**
     * Simulates filling an exit order (TP or SL hit).
     */
    FillResult fillExit(String ticker, String direction, int quantity, double exitPrice, ZonedDateTime timestamp);
}
