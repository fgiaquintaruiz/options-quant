package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Calculates risk parameters (TP/SL) based on ATR from the 1H timeframe.
 */
public class RiskCalculator {

    private static final double TP_MULTIPLIER = 1.5;
    private static final double SL_MULTIPLIER = 1.0;
    private static final double MAX_TARGET_PCT = 0.009; // 0.9% of stock price

    public static TradePlan generatePlan(StrategyData data, String ticker, ZonedDateTime entryTime, boolean isCall, double entryPrice) {
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        if (series1h == null || series1h.isEmpty()) {
            return new TradePlan(entryPrice, entryPrice, entryPrice, isCall, LocalTime.of(15, 55));
        }

        int index1h = data.getIndexForTime(series1h, entryTime);
        if (index1h < 0) index1h = series1h.getEndIndex();

        double atr = new ATRIndicator(series1h, 14).getValue(index1h).doubleValue();

        double tpDist = atr * TP_MULTIPLIER;
        double slDist = atr * SL_MULTIPLIER;

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
