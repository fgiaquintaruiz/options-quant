package com.fgiaquinta.optionsquant.analyzers;

import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.num.Num; // IMPORTANTE: Importar la interfaz Num
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

public class VolatilityAnalyzer {

    private VolatilityAnalyzer() {
        // Utility class
    }

    public static double getBollingerBandWidthPct(BarSeries series, int index, int period, double multiplier) {
        if (index < period || index < 0) return 100.0;

        ClosePriceIndicator close = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(close, period);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(close, period);

        // Convertimos el multiplier (double) al tipo Num que requiere la serie
        Num k = series.numOf(multiplier);

        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(sma);

        // Ahora pasamos 'k' (Num) en lugar de 'multiplier' (double)
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, sd, k);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, sd, k);

        double upper = bbUpper.getValue(index).doubleValue();
        double lower = bbLower.getValue(index).doubleValue();
        double middle = bbMiddle.getValue(index).doubleValue();

        if (middle == 0) return 0;

        return ((upper - lower) / middle) * 100.0;
    }
    
    public static double calculateATR(BarSeries series, int period) {
        if (series.getBarCount() < period) return 0.0;
        return new ATRIndicator(series, period).getValue(series.getEndIndex()).doubleValue();
    }

    public static double calculateATR(BarSeries series, int period, int targetIndex) {
        // 1. Si no hay suficientes datos históricos hasta este índice, el ATR es 0
        // (Restamos 1 porque los índices en ta4j empiezan en 0)
        if (targetIndex < period - 1 || series.getBarCount() < period) {
            return 0.0;
        }

        // 2. Calcula el ATR y extrae matemáticamente el valor que tenía en esa vela específica
        return new org.ta4j.core.indicators.ATRIndicator(series, period).getValue(targetIndex).doubleValue();
    }
}