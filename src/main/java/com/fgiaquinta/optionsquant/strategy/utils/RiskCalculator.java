package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerStrategyProfile;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Calculates risk parameters (TP/SL) based on ATR from the 1H timeframe.
 * Supports per-strategy ATR multipliers for fine-tuned risk management.
 */
public class RiskCalculator {

    private static final double DEFAULT_TP_MULTIPLIER = 2.5;
    private static final double DEFAULT_SL_MULTIPLIER = 2.0;
    private static final double MAX_TARGET_PCT = 0.015; // Increased from 0.9% to 1.5% to allow wider stops

    // Per-strategy ATR multipliers - DRAMATICALLY WIDENED based on backtest analysis
    // Backtest showed MaxDD of $2,000-13,000 before SL hits - stops were WAY too tight
    // New multipliers are 2-3x wider to give trades room to breathe
    private static final Map<String, Double> STRATEGY_SL_MULTIPLIERS = new HashMap<>();
    private static final Map<String, Double> STRATEGY_TP_MULTIPLIERS = new HashMap<>();

    static {
        // SL multipliers - WIDENED significantly based on backtest MaxDD analysis
        // C1: Was 1.3x, now 2.5x - had 0% win rate, stops hit in 1-2 hours
        STRATEGY_SL_MULTIPLIERS.put("c1squeezecall", 2.5);
        // C2: Was 1.2x, now 2.2x - needs room for pullback continuation
        STRATEGY_SL_MULTIPLIERS.put("c2trendcall", 2.2);
        // C3: Was 1.4x, now 2.8x - had 0% win rate, worst performer
        STRATEGY_SL_MULTIPLIERS.put("c3bouncecall", 2.8);
        // C4: Was 1.5x, now 3.0x - opening volatility is extreme
        STRATEGY_SL_MULTIPLIERS.put("c4openingcall", 3.0);
        // C5: Was 1.4x, now 2.5x - gap reversals need room
        STRATEGY_SL_MULTIPLIERS.put("c5continuationcall", 2.5);
        // C6: Was 1.3x, now 2.5x - had 0% win rate
        STRATEGY_SL_MULTIPLIERS.put("c6reversalcall", 2.5);
        // P1: Was 1.2x, now 2.0x - this strategy was WINNING (64%), keep tighter
        STRATEGY_SL_MULTIPLIERS.put("p1squeezeput", 2.0);
        // P2: Was 1.1x, now 2.0x - needs more room
        STRATEGY_SL_MULTIPLIERS.put("p2trendput", 2.0);
        // P3: Was 1.2x, now 2.2x - 33% win rate, needs wider stops
        STRATEGY_SL_MULTIPLIERS.put("p3bounceput", 2.2);
        // P4: Was 1.3x, now 2.5x - opening puts need room
        STRATEGY_SL_MULTIPLIERS.put("p4openingput", 2.5);
        // P5: Was 1.2x, now 2.2x
        STRATEGY_SL_MULTIPLIERS.put("p5continuationput", 2.2);
        // P6: Was 1.3x, now 2.2x - this was breakeven, keep moderate
        STRATEGY_SL_MULTIPLIERS.put("p6reversalput", 2.2);

        // TP multipliers - INCREASED to improve risk/reward ratio
        // Target: 1.5:1 reward-to-risk ratio minimum
        STRATEGY_TP_MULTIPLIERS.put("c1squeezecall", 3.5);  // Was 1.6x
        STRATEGY_TP_MULTIPLIERS.put("c2trendcall", 3.2);    // Was 1.5x
        STRATEGY_TP_MULTIPLIERS.put("c3bouncecall", 3.8);   // Was 1.4x
        STRATEGY_TP_MULTIPLIERS.put("c4openingcall", 4.0);  // Was 1.3x
        STRATEGY_TP_MULTIPLIERS.put("c5continuationcall", 3.5); // Was 1.4x
        STRATEGY_TP_MULTIPLIERS.put("c6reversalcall", 3.5); // Was 1.5x
        STRATEGY_TP_MULTIPLIERS.put("p1squeezeput", 3.0);   // Was 1.5x - winning strategy
        STRATEGY_TP_MULTIPLIERS.put("p2trendput", 3.0);     // Was 1.5x
        STRATEGY_TP_MULTIPLIERS.put("p3bounceput", 3.2);    // Was 1.4x
        STRATEGY_TP_MULTIPLIERS.put("p4openingput", 3.5);   // Was 1.4x
        STRATEGY_TP_MULTIPLIERS.put("p5continuationput", 3.2); // Was 1.5x
        STRATEGY_TP_MULTIPLIERS.put("p6reversalput", 3.2);  // Was 1.4x
    }

    /**
     * Optional TP/SL multiplier deltas for a single backtest run (e.g. /retest trial).
     * Uses {@link AtomicReference} so parallel ticker workers in {@link com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine}
     * see the same trial values (not {@link ThreadLocal}).
     */
    private static final AtomicReference<double[]> RETEST_MULTIPLIER_DELTAS = new AtomicReference<>();

    /**
     * Adds to TP and SL ATR multipliers for the duration of one backtest (all worker threads).
     *
     * @param tpDelta added to TP multiplier (e.g. 0.2)
     * @param slDelta added to SL multiplier (e.g. 0.3 to widen stop)
     */
    public static void setRetestMultiplierDeltas(double tpDelta, double slDelta) {
        RETEST_MULTIPLIER_DELTAS.set(new double[] {tpDelta, slDelta});
    }

    public static void clearRetestMultiplierDeltas() {
        RETEST_MULTIPLIER_DELTAS.set(null);
    }

    /**
     * Maps {@link com.fgiaquinta.optionsquant.strategy.TradingStrategy#getName()} style strings ("p5 continuation")
     * to static map keys ("p5continuationput").
     */
    public static String resolveMultiplierMapKey(String strategyName, boolean isCall) {
        if (strategyName == null || strategyName.isBlank()) {
            return "";
        }
        String compact = strategyName.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String suffixed = compact + (isCall ? "call" : "put");
        if (STRATEGY_SL_MULTIPLIERS.containsKey(suffixed)) {
            return suffixed;
        }
        if (STRATEGY_TP_MULTIPLIERS.containsKey(suffixed)) {
            return suffixed;
        }
        if (STRATEGY_SL_MULTIPLIERS.containsKey(compact)) {
            return compact;
        }
        if (STRATEGY_TP_MULTIPLIERS.containsKey(compact)) {
            return compact;
        }
        return suffixed;
    }

    public static double baseTpMultiplier(String mapKey) {
        return STRATEGY_TP_MULTIPLIERS.getOrDefault(mapKey, DEFAULT_TP_MULTIPLIER);
    }

    public static double baseSlMultiplier(String mapKey) {
        return STRATEGY_SL_MULTIPLIERS.getOrDefault(mapKey, DEFAULT_SL_MULTIPLIER);
    }

    /**
     * TP/SL multipliers after applying global maps and optional per-ticker+strategy overrides from memory.
     */
    static double[] resolveMultipliers(String strategyName, boolean isCall, TickerStrategyProfile profile) {
        String mapKey = strategyName != null ? resolveMultiplierMapKey(strategyName, isCall) : "";
        double tp = baseTpMultiplier(mapKey);
        double sl = baseSlMultiplier(mapKey);
        if (profile != null) {
            if (profile.getTpAtrMultOverride() != null) {
                tp = profile.getTpAtrMultOverride();
            }
            if (profile.getSlAtrMultOverride() != null) {
                sl = profile.getSlAtrMultOverride();
            }
        }
        return new double[] {tp, sl};
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
     * Generates a TradePlan with strategy-specific ATR multipliers.
     *
     * @param profile optional per-ticker+strategy overrides from {@link com.fgiaquinta.optionsquant.service.TickerMemory}
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

        double[] resolved = resolveMultipliers(strategy, isCall, profile);
        double tpMultiplier = resolved[0];
        double slMultiplier = resolved[1];

        double[] trial = RETEST_MULTIPLIER_DELTAS.get();
        if (trial != null && trial.length >= 2) {
            tpMultiplier += trial[0];
            slMultiplier += trial[1];
        }
        tpMultiplier = Math.max(0.25, tpMultiplier);
        slMultiplier = Math.max(0.25, slMultiplier);

        double tpDist = atr * tpMultiplier;
        double slDist = atr * slMultiplier;

        // Cap at 0.9% of stock price for options
        double maxTpDist = entryPrice * MAX_TARGET_PCT;
        if (tpDist > maxTpDist) {
            tpDist = maxTpDist;
        }

        if (tpDist < 0.25) tpDist = 0.25;
        if (slDist < 0.25) slDist = 0.25;

        double tp = Math.round((isCall ? entryPrice + tpDist : entryPrice - tpDist) * 100.0) / 100.0;
        double sl = Math.round((isCall ? entryPrice - slDist : entryPrice + slDist) * 100.0) / 100.0;

        return new TradePlan(entryPrice, tp, sl, isCall, LocalTime.of(15, 55), atr);
    }
}
