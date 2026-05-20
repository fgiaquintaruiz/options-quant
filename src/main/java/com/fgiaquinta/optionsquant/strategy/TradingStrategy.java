package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.strategy.data.StrategyData;

import java.time.ZonedDateTime;

public interface TradingStrategy {

    /**
     * Returns the strategy name (e.g., "c1 squeeze", "c2 trend", "p1 squeeze")
     * Strips direction suffix (call/put) and adds space between number and name.
     */
    default String getName() {
        String name = this.getClass().getSimpleName().replace("Strategy", "").toLowerCase();
        // Remove direction suffix (call/put) - e.g., "c1squeezecall" -> "c1squeeze"
        name = name.replaceAll("(call|put)$", "");
        // Add space between number and name - e.g., "c1squeeze" -> "c1 squeeze"
        name = name.replaceAll("(c\\d|p\\d)([a-z])", "$1 $2");
        return name;
    }

    /**
     * Evaluates if the strategy should trigger a signal.
     * @param ticker The symbol to trade (e.g., "MSFT")
     * @param data The strategy data wrapper containing all timeframes
     * @param currentTime The current time (in NY timezone)
     * @return true if all rules are met, false otherwise
     */
    boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime);

    /**
     * Returns true if this strategy trades call options, false for puts.
     * Used to determine option direction without fragile string matching on class names.
     */
    boolean isCall();
}
