package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class P2TrendPutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P2TrendPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series1h);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma50 = new SMAIndicator(closePrice, 50);

        // REGLA: El precio y la media corta están por debajo de la media larga
        double currentClose = series1h.getBar(index).getClosePrice().doubleValue();
        return currentClose < sma20.getValue(index).doubleValue() &&
                sma20.getValue(index).doubleValue() < sma50.getValue(index).doubleValue();
    }

    @Override
    public double calculateTP(double entryPrice) {
        double tpMult = ConfigLoader.getConfig().getParam("trend", "tp");
        return Math.round((entryPrice * (1 - tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double slMult = ConfigLoader.getConfig().getParam("trend", "sl");
        return Math.round((entryPrice * (1 + slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "p2_trend_put";
    }
}