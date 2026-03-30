package com.fgiaquinta.optionsquant.analyzers;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class ChannelAnalyzer {

    private ChannelAnalyzer() {
        // Utility class: purely mathematical, no instantiation needed
    }

    /**
     * Verifies if the 4 moving averages (20, 40, 100, 200) have been moving laterally
     * and entangled within a tight channel over the last 'lookbackBars' periods.
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

        // Requiere compresión estricta en al menos el 85% de las velas analizadas
        double consistency = (double) lateralBarsCount / lookbackBars;
        return consistency >= 0.85;
    }
}   