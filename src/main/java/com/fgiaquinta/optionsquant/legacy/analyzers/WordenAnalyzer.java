package com.fgiaquinta.optionsquant.analyzers;

import com.fgiaquinta.optionsquant.indicators.WordenStochasticIndicator;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class WordenAnalyzer {

    /**
     * Calcula el Worden Stochastic suavizado.
     * @param period Periodo de cálculo (ej. 12)
     * @param smoothing Periodo de suavizado SMA (ej. 3)
     */
    public static double getWordenStochastic(BarSeries series, int index, int period, int smoothing) {
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        WordenStochasticIndicator worden = new WordenStochasticIndicator(close, period);

        // Aplicamos suavizado SMA sobre el indicador Worden
        SMAIndicator smoothedWorden = new SMAIndicator(worden, smoothing);

        return smoothedWorden.getValue(index).doubleValue();
    }
}