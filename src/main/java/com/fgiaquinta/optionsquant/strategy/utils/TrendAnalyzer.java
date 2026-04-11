package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.Candle;
import org.ta4j.core.BarSeries;

import java.util.List;

/**
 * Projects bullish/bearish trend lines from swing highs/lows.
 * Migrated from legacy TrendAnalyzer.java.
 *
 * Useful for:
 * - Identifying resistance/support levels
 * - Detecting trend line breaks (reversal strategies C6/P6)
 * - Confirming trend direction
 */
public class TrendAnalyzer {

    private TrendAnalyzer() {}

    /**
     * Calculates the projected value of a bearish trend line (resistance) at the current index.
     * Finds the last two major Swing Highs (peaks) and projects the line forward.
     *
     * @param series The ta4j BarSeries
     * @param currentIndex Current bar index
     * @param lookback Number of bars to search for swing highs
     * @return Projected trend line value, or -1 if valid peaks cannot be found
     */
    public static double getBearishTrendLineValue(BarSeries series, int currentIndex, int lookback) {
        if (currentIndex < lookback) return -1;

        int peak1Index = -1;
        int peak2Index = -1;

        // Search backward to find the two most recent distinct Swing Highs
        for (int i = currentIndex - 2; i > currentIndex - lookback; i--) {
            if (isSwingHigh(series, i)) {
                if (peak1Index == -1) {
                    peak1Index = i;
                } else if (peak2Index == -1) {
                    // Ensure the older peak is strictly higher to form a valid bearish slope
                    if (series.getBar(i).getHighPrice().doubleValue() > series.getBar(peak1Index).getHighPrice().doubleValue()) {
                        peak2Index = i;
                        break;
                    }
                }
            }
        }

        if (peak1Index == -1 || peak2Index == -1) return -1;

        double y1 = series.getBar(peak2Index).getHighPrice().doubleValue(); // Older, higher peak
        double y2 = series.getBar(peak1Index).getHighPrice().doubleValue(); // Newer, lower peak

        // Calculate slope: m = (y2 - y1) / (x2 - x1)
        double slope = (y2 - y1) / (peak1Index - peak2Index);

        // Calculate projected Y at the current index: y = m * (x - x1) + y1
        return slope * (currentIndex - peak2Index) + y1;
    }

    /**
     * Calculates the projected value of a bullish trend line (support) at the current index.
     * Finds the last two major Swing Lows (valleys) and projects the line forward.
     *
     * @param series The ta4j BarSeries
     * @param currentIndex Current bar index
     * @param lookback Number of bars to search for swing lows
     * @return Projected trend line value, or -1 if valid valleys cannot be found
     */
    public static double getBullishTrendLineValue(BarSeries series, int currentIndex, int lookback) {
        if (currentIndex < lookback) return -1;

        int valley1Index = -1;
        int valley2Index = -1;

        for (int i = currentIndex - 2; i > currentIndex - lookback; i--) {
            if (isSwingLow(series, i)) {
                if (valley1Index == -1) {
                    valley1Index = i;
                } else if (valley2Index == -1) {
                    // Ensure the older valley is strictly lower to form a valid bullish slope
                    if (series.getBar(i).getLowPrice().doubleValue() < series.getBar(valley1Index).getLowPrice().doubleValue()) {
                        valley2Index = i;
                        break;
                    }
                }
            }
        }

        if (valley1Index == -1 || valley2Index == -1) return -1;

        double y1 = series.getBar(valley2Index).getLowPrice().doubleValue();
        double y2 = series.getBar(valley1Index).getLowPrice().doubleValue();

        double slope = (y2 - y1) / (valley1Index - valley2Index);
        return slope * (currentIndex - valley2Index) + y1;
    }

    /**
     * Candle-based version: checks if the latest candle is near a trend line.
     *
     * @param candles List of candles (sorted chronologically)
     * @param lookback Number of bars to search for swing points
     * @return true if current price is within 0.5% of the bearish trend line (resistance test)
     */
    public static boolean isNearBearishTrendLine(List<Candle> candles, int lookback) {
        if (candles.size() < lookback + 2) return false;

        // Find two swing highs in the Candle list
        int n = candles.size();
        int peak1Idx = -1;
        int peak2Idx = -1;

        for (int i = n - 3; i > n - lookback; i--) {
            if (isSwingHighCandle(candles, i)) {
                if (peak1Idx == -1) {
                    peak1Idx = i;
                } else if (peak2Idx == -1) {
                    if (candles.get(i).high() > candles.get(peak1Idx).high()) {
                        peak2Idx = i;
                        break;
                    }
                }
            }
        }

        if (peak1Idx == -1 || peak2Idx == -1) return false;

        double y1 = candles.get(peak2Idx).high();
        double y2 = candles.get(peak1Idx).high();
        double slope = (y2 - y1) / (peak1Idx - peak2Idx);
        double projectedValue = slope * ((n - 1) - peak2Idx) + y1;

        double currentPrice = candles.get(n - 1).close();
        return Math.abs(currentPrice - projectedValue) / projectedValue < 0.005; // Within 0.5%
    }

    /**
     * Candle-based version: checks if the latest candle is near a bullish trend line.
     *
     * @param candles List of candles (sorted chronologically)
     * @param lookback Number of bars to search for swing points
     * @return true if current price is within 0.5% of the bullish trend line (support test)
     */
    public static boolean isNearBullishTrendLine(List<Candle> candles, int lookback) {
        if (candles.size() < lookback + 2) return false;

        int n = candles.size();
        int valley1Idx = -1;
        int valley2Idx = -1;

        for (int i = n - 3; i > n - lookback; i--) {
            if (isSwingLowCandle(candles, i)) {
                if (valley1Idx == -1) {
                    valley1Idx = i;
                } else if (valley2Idx == -1) {
                    if (candles.get(i).low() < candles.get(valley1Idx).low()) {
                        valley2Idx = i;
                        break;
                    }
                }
            }
        }

        if (valley1Idx == -1 || valley2Idx == -1) return false;

        double y1 = candles.get(valley2Idx).low();
        double y2 = candles.get(valley1Idx).low();
        double slope = (y2 - y1) / (valley1Idx - valley2Idx);
        double projectedValue = slope * ((n - 1) - valley2Idx) + y1;

        double currentPrice = candles.get(n - 1).close();
        return Math.abs(currentPrice - projectedValue) / projectedValue < 0.005; // Within 0.5%
    }

    // ===== Swing detection helpers =====

    /**
     * A Swing High requires the bar to be higher than the 2 bars before and the 2 bars after.
     */
    private static boolean isSwingHigh(BarSeries series, int index) {
        if (index < 2 || index > series.getEndIndex() - 2) return false;

        double high = series.getBar(index).getHighPrice().doubleValue();
        return high > series.getBar(index - 1).getHighPrice().doubleValue() &&
                high > series.getBar(index - 2).getHighPrice().doubleValue() &&
                high > series.getBar(index + 1).getHighPrice().doubleValue() &&
                high > series.getBar(index + 2).getHighPrice().doubleValue();
    }

    /**
     * A Swing Low requires the bar to be lower than the 2 bars before and the 2 bars after.
     */
    private static boolean isSwingLow(BarSeries series, int index) {
        if (index < 2 || index > series.getEndIndex() - 2) return false;

        double low = series.getBar(index).getLowPrice().doubleValue();
        return low < series.getBar(index - 1).getLowPrice().doubleValue() &&
                low < series.getBar(index - 2).getLowPrice().doubleValue() &&
                low < series.getBar(index + 1).getLowPrice().doubleValue() &&
                low < series.getBar(index + 2).getLowPrice().doubleValue();
    }

    /**
     * Swing High for Candle domain objects.
     */
    private static boolean isSwingHighCandle(List<Candle> candles, int index) {
        if (index < 2 || index >= candles.size() - 2) return false;

        double high = candles.get(index).high();
        return high > candles.get(index - 1).high() &&
                high > candles.get(index - 2).high() &&
                high > candles.get(index + 1).high() &&
                high > candles.get(index + 2).high();
    }

    /**
     * Swing Low for Candle domain objects.
     */
    private static boolean isSwingLowCandle(List<Candle> candles, int index) {
        if (index < 2 || index >= candles.size() - 2) return false;

        double low = candles.get(index).low();
        return low < candles.get(index - 1).low() &&
                low < candles.get(index - 2).low() &&
                low < candles.get(index + 1).low() &&
                low < candles.get(index + 2).low();
    }
}
