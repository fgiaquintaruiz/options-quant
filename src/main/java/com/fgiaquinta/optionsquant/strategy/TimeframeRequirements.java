package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;

import java.util.Set;

/**
 * Declares which {@link TimeFrame} timeseries a strategy needs in order to run.
 *
 * <p>Used by {@code StrategyScannerService} to decide whether a given strategy
 * can be evaluated for a ticker. Strategies whose required timeframes are not
 * available are skipped — but the ticker is NOT rejected, so other strategies
 * can still run.
 *
 * <p>Decoupling ticker viability from strategy viability lets the scanner make
 * progress on tickers with partial data (e.g., when MIN_5 didn't load).
 */
public interface TimeframeRequirements {

    /**
     * The exact set of timeframes this strategy reads from {@code StrategyData}.
     * Must be non-null and non-empty for any production strategy.
     */
    Set<TimeFrame> requiredTimeframes();
}
