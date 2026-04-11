package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.Candle;
import org.ta4j.core.BarSeries;

import java.util.List;

/**
 * Calculates gap percentages between consecutive candles.
 * Migrated from legacy GapAnalyzer.java.
 *
 * Useful for:
 * - Detecting opening gaps (C4/C5 strategies)
 * - Identifying gap-fill opportunities
 * - Confirming breakout strength
 */
public class GapAnalyzer {

    private GapAnalyzer() {}

    /**
     * Calculates the gap percentage between the previous close and current open.
     * Positive = gap up, Negative = gap down.
     *
     * @param series The ta4j BarSeries
     * @param currentIndex Current bar index
     * @return Gap percentage (e.g., 2.5 = 2.5% gap up)
     */
    public static double getGapPercentage(BarSeries series, int currentIndex) {
        if (currentIndex < 1) return 0.0;

        double previousClose = series.getBar(currentIndex - 1).getClosePrice().doubleValue();
        double currentOpen = series.getBar(currentIndex).getOpenPrice().doubleValue();

        return ((currentOpen - previousClose) / previousClose) * 100.0;
    }

    /**
     * Calculates the gap percentage using Candle domain objects.
     * Positive = gap up, Negative = gap down.
     *
     * @param candles List of candles (sorted chronologically)
     * @param index Index of the current candle
     * @return Gap percentage (e.g., 2.5 = 2.5% gap up)
     */
    public static double getGapPercentage(List<Candle> candles, int index) {
        if (index < 1 || index >= candles.size()) return 0.0;

        double previousClose = candles.get(index - 1).close();
        double currentOpen = candles.get(index).open();

        return ((currentOpen - previousClose) / previousClose) * 100.0;
    }

    /**
     * Checks if there's a significant gap (above threshold).
     *
     * @param series The ta4j BarSeries
     * @param currentIndex Current bar index
     * @param thresholdPct Minimum gap percentage to consider significant
     * @return true if gap magnitude exceeds threshold
     */
    public static boolean hasSignificantGap(BarSeries series, int currentIndex, double thresholdPct) {
        double gap = Math.abs(getGapPercentage(series, currentIndex));
        return gap >= thresholdPct;
    }
}
