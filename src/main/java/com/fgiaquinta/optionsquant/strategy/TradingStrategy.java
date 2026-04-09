package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.strategy.data.StrategyData;

import java.time.ZonedDateTime;

public interface TradingStrategy {

    /**
     * Returns the strategy name (e.g., "c1_squeeze_call")
     */
    default String getName() {
        return this.getClass().getSimpleName().replace("Strategy", "").toLowerCase();
    }

    /**
     * Evaluates if the strategy should trigger a signal.
     * @param ticker The symbol to trade (e.g., "MSFT")
     * @param data The strategy data wrapper containing all timeframes
     * @param currentTime The current time (in NY timezone)
     * @return true if all rules are met, false otherwise
     */
    boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime);
}
