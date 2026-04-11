package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.domain.Candle;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.List;

/**
 * Detects SMA lateral channel compression (20/40/100/200 SMA entanglement).
 * Migrated from legacy ChannelAnalyzer.java.
 *
 * Used by squeeze strategies (C1/P1) to verify that the market is in a tight
 * consolidation phase before expecting a breakout.
 */
public class ChannelAnalyzer {

    private ChannelAnalyzer() {}

    /**
     * Verifies if the 4 moving averages (20, 40, 100, 200) have been moving laterally
     * and entangled within a tight channel over the last {@code lookbackBars} periods.
     *
     * @param series The ta4j BarSeries
     * @param currentIndex Current bar index
     * @param lookbackBars Number of bars to check for compression
     * @param maxThresholdPercentage Maximum allowed SMA spread as a percentage (e.g., 4.0 = 4%)
     * @return true if SMAs were compressed in at least 85% of the lookback period
     */
    public static boolean isSmaLateralChannel(BarSeries series, int currentIndex, int lookbackBars, double maxThresholdPercentage) {
        if (currentIndex < 200 + lookbackBars) {
            return false;
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma40 = new SMAIndicator(closePrice, 40);
        SMAIndicator sma100 = new SMAIndicator(closePrice, 100);
        SMAIndicator sma200 = new SMAIndicator(closePrice, 200);

        int lateralBarsCount = 0;

        for (int i = currentIndex - lookbackBars; i <= currentIndex; i++) {
            double v20 = sma20.getValue(i).doubleValue();
            double v40 = sma40.getValue(i).doubleValue();
            double v100 = sma100.getValue(i).doubleValue();
            double v200 = sma200.getValue(i).doubleValue();

            double maxSma = Math.max(Math.max(v20, v40), Math.max(v100, v200));
            double minSma = Math.min(Math.min(v20, v40), Math.min(v100, v200));

            double distancePct = ((maxSma - minSma) / minSma) * 100.0;

            if (distancePct <= maxThresholdPercentage) {
                lateralBarsCount++;
            }
        }

        // Requires strict compression in at least 85% of analyzed bars
        double consistency = (double) lateralBarsCount / lookbackBars;
        return consistency >= 0.85;
    }

    /**
     * Checks if the 4 SMAs (20, 40, 100, 200) are currently entangled
     * (spread within the threshold percentage) at the given index.
     *
     * @param series The ta4j BarSeries
     * @param currentIndex Current bar index
     * @param maxThresholdPercentage Maximum allowed SMA spread as a percentage
     * @return true if all 4 SMAs are within the threshold
     */
    public static boolean areSmasEntangled(BarSeries series, int currentIndex, double maxThresholdPercentage) {
        if (currentIndex < 200) return false;

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        double v20 = new SMAIndicator(closePrice, 20).getValue(currentIndex).doubleValue();
        double v40 = new SMAIndicator(closePrice, 40).getValue(currentIndex).doubleValue();
        double v100 = new SMAIndicator(closePrice, 100).getValue(currentIndex).doubleValue();
        double v200 = new SMAIndicator(closePrice, 200).getValue(currentIndex).doubleValue();

        double maxSma = Math.max(Math.max(v20, v40), Math.max(v100, v200));
        double minSma = Math.min(Math.min(v20, v40), Math.min(v100, v200));

        double distancePct = ((maxSma - minSma) / minSma) * 100.0;
        return distancePct <= maxThresholdPercentage;
    }
}
