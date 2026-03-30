package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.utils.MarketMath;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class P1SqueezePutStrategy implements TradingStrategy {
    @Override public String getName() { return "P1_SQUEEZE_PUT"; }

    @Override
    public boolean isTriggered(int i, BarSeries series, BarSeries baseline) {
        if (i < 200) return false;
        ClosePriceIndicator cp = new ClosePriceIndicator(series);
        double c = cp.getValue(i).doubleValue();
        double s200 = new SMAIndicator(cp, 200).getValue(i).doubleValue();
        return c < s200 && MarketMath.calculateRsRank(series, baseline, i) < 30;
    }

    @Override
    public double calculateTP(double entryPrice) {
        return entryPrice - 1.50; // En un PUT, el TP está por debajo
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double stopMove = (ticker.equals("NVDA") || ticker.equals("TSLA")) ? 4.50 : 3.00;
        return entryPrice + stopMove; // En un PUT, el SL está por encima
    }
}