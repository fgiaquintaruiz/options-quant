package com.fgiaquinta.optionsquant.analyzers;

import org.ta4j.core.BarSeries;

public class TrendAnalyzer {

    private TrendAnalyzer() {
        // Utility class: purely mathematical, no instantiation needed
    }

    /**
     * Calculates the projected value of a bearish trend line (resistance) at the current index.
     * It finds the last two major Swing Highs (peaks) and projects the line forward.
     * Returns -1 if valid peaks cannot be found.
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
        double projectedValue = slope * (currentIndex - peak2Index) + y1;

        return projectedValue;
    }

    /**
     * Calculates the projected value of a bullish trend line (support) at the current index.
     * It finds the last two major Swing Lows (valleys) and projects the line forward.
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
        double projectedValue = slope * (currentIndex - valley2Index) + y1;

        return projectedValue;
    }

    // A Swing High requires the bar to be higher than the 2 bars before and the 2 bars after
    private static boolean isSwingHigh(BarSeries series, int index) {
        if (index < 2 || index > series.getEndIndex() - 2) return false;

        double high = series.getBar(index).getHighPrice().doubleValue();
        return high > series.getBar(index - 1).getHighPrice().doubleValue() &&
                high > series.getBar(index - 2).getHighPrice().doubleValue() &&
                high > series.getBar(index + 1).getHighPrice().doubleValue() &&
                high > series.getBar(index + 2).getHighPrice().doubleValue();
    }

    // A Swing Low requires the bar to be lower than the 2 bars before and the 2 bars after
    private static boolean isSwingLow(BarSeries series, int index) {
        if (index < 2 || index > series.getEndIndex() - 2) return false;

        double low = series.getBar(index).getLowPrice().doubleValue();
        return low < series.getBar(index - 1).getLowPrice().doubleValue() &&
                low < series.getBar(index - 2).getLowPrice().doubleValue() &&
                low < series.getBar(index + 1).getLowPrice().doubleValue() &&
                low < series.getBar(index + 2).getLowPrice().doubleValue();
    }
}