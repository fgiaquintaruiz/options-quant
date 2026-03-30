package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;

public class P1SqueezePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P1SqueezePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        // REGLA 1: Detección de Squeeze (Baja volatilidad histórica)
        double width = VolatilityAnalyzer.getBollingerBandWidthPct(series1h, index, 20, 2.0);
        if (width > 1.5) return false; // El umbral se puede mover al YAML si deseas

        // REGLA 2: Confirmación bajista (Cierre por debajo de la media de 20)
        double close = series1h.getBar(index).getClosePrice().doubleValue();
        double sma20 = new org.ta4j.core.indicators.SMAIndicator(
                new org.ta4j.core.indicators.helpers.ClosePriceIndicator(series1h), 20).getValue(index).doubleValue();

        return close < sma20;
    }

    @Override
    public double calculateTP(double entryPrice) {
        double tpMult = ConfigLoader.getConfig().getParam("squeeze", "tp");
        return Math.round((entryPrice * (1 - tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double slMult = ConfigLoader.getConfig().getParam("squeeze", "sl");
        return Math.round((entryPrice * (1 + slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "p1_squeeze_put";
    }
}