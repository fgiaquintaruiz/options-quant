package com.fgiaquinta.optionsquant.analyzers;

import org.ta4j.core.BarSeries;

public class GapAnalyzer {

    private GapAnalyzer() {
        // Utility class
    }

    /**
     * Calcula el Gap (salto) porcentual entre el cierre de la vela anterior y la apertura actual.
     * Retorna un valor positivo para Gap Up y negativo para Gap Down.
     */
    public static double getGapPercentage(BarSeries series, int currentIndex) {
        if (currentIndex < 1) return 0.0;

        double previousClose = series.getBar(currentIndex - 1).getClosePrice().doubleValue();
        double currentOpen = series.getBar(currentIndex).getOpenPrice().doubleValue();

        return ((currentOpen - previousClose) / previousClose) * 100.0;
    }
}