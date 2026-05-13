package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerStrategyProfile;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Calculates risk parameters (TP/SL) using fixed percentages calibrated for ITM options
 * with delta ≈ 0.60 and 48-72h expiration. ATR is still computed and stored in TradePlan
 * for analysis purposes but is not used for target distances.
 *
 * <p>Business rule:
 * <ul>
 *   <li>TP = entryPrice * (1 + 0.0067) for CALL (underlying +1% → contract +10%)</li>
 *   <li>TP = entryPrice * (1 - 0.0067) for PUT</li>
 *   <li>SL = entryPrice * (1 - 0.0033) for CALL</li>
 *   <li>SL = entryPrice * (1 + 0.0033) for PUT</li>
 * </ul>
 */
public class RiskCalculator {

    /** Fixed TP percentage distance from entry (0.67%). */
    public static final double TP_PCT = 0.0067;

    /** Fixed SL percentage distance from entry (0.33%). */
    public static final double SL_PCT = 0.0033;

    /**
     * No-op retained for API compatibility with BacktestEngine (/retest trial).
     * ATR multiplier deltas are no longer applied — targets use fixed percentages.
     */
    public static void setRetestMultiplierDeltas(double tpDelta, double slDelta) {
        // no-op: fixed-percentage model does not use ATR multipliers
    }

    /** No-op retained for API compatibility with BacktestEngine. */
    public static void clearRetestMultiplierDeltas() {
        // no-op
    }

    /**
     * @deprecated ATR multipliers are no longer used for TP/SL targets.
     *             Retained for API compatibility with PromoteRiskService.
     */
    @Deprecated
    public static String resolveMultiplierMapKey(String strategyName, boolean isCall) {
        return strategyName != null ? strategyName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "") + (isCall ? "call" : "put") : "";
    }

    /**
     * @deprecated ATR multipliers are no longer used for TP/SL targets.
     *             Retained for API compatibility with PromoteRiskService.
     */
    @Deprecated
    public static double baseTpMultiplier(String mapKey) {
        return 0.0;
    }

    /**
     * @deprecated ATR multipliers are no longer used for TP/SL targets.
     *             Retained for API compatibility with PromoteRiskService.
     */
    @Deprecated
    public static double baseSlMultiplier(String mapKey) {
        return 0.0;
    }

    public static TradePlan generatePlan(StrategyData data, String ticker, ZonedDateTime entryTime, boolean isCall, double entryPrice) {
        return generatePlan(data, ticker, entryTime, isCall, entryPrice, null, null);
    }

    /**
     * Same as {@link #generatePlan(StrategyData, String, ZonedDateTime, boolean, double, String, TickerStrategyProfile)} without ticker memory overrides.
     */
    public static TradePlan generatePlan(StrategyData data, String ticker, ZonedDateTime entryTime, boolean isCall, double entryPrice, String strategy) {
        return generatePlan(data, ticker, entryTime, isCall, entryPrice, strategy, null);
    }

    /**
     * Generates a TradePlan using fixed-percentage TP/SL targets.
     * ATR is computed from the 1H series and stored in the plan for downstream analysis.
     *
     * @param profile retained for API compatibility — no longer affects TP/SL distances
     */
    public static TradePlan generatePlan(StrategyData data, String ticker, ZonedDateTime entryTime, boolean isCall, double entryPrice,
            String strategy, TickerStrategyProfile profile) {
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        if (series1h == null || series1h.isEmpty()) {
            return new TradePlan(entryPrice, entryPrice, entryPrice, isCall, LocalTime.of(15, 55));
        }

        int index1h = data.getIndexForTime(series1h, entryTime);
        if (index1h < 0) index1h = series1h.getEndIndex();

        double atr = new ATRIndicator(series1h, 14).getValue(index1h).doubleValue();

        double tp = Math.round((isCall ? entryPrice * (1 + TP_PCT) : entryPrice * (1 - TP_PCT)) * 100.0) / 100.0;
        double sl = Math.round((isCall ? entryPrice * (1 - SL_PCT) : entryPrice * (1 + SL_PCT)) * 100.0) / 100.0;

        return new TradePlan(entryPrice, tp, sl, isCall, LocalTime.of(15, 55), atr);
    }
}
