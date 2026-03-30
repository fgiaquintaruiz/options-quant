package com.fgiaquinta.optionsquant.strategies;

import org.ta4j.core.BarSeries;

public interface TradingStrategy {
    String getName();
    boolean isTriggered(int i, BarSeries series, BarSeries baseline);

    // Nuevos métodos para centralizar el riesgo
    double calculateTP(double entryPrice);
    double calculateSL(double entryPrice, String ticker);
}