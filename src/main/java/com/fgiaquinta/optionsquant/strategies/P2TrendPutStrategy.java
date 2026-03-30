package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.MarketMath;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class P2TrendPutStrategy implements TradingStrategy {
    private final IbkrService ibkr;
    public P2TrendPutStrategy(IbkrService ibkr) { this.ibkr = ibkr; }

    @Override public String getName() { return "P2_TREND_PUT"; }

    @Override
    public boolean isTriggered(int i, BarSeries series, BarSeries baseline) {
        if (i < 200) return false;
        ClosePriceIndicator cp = new ClosePriceIndicator(series);
        double c = cp.getValue(i).doubleValue();
        double e8 = new EMAIndicator(cp, 8).getValue(i).doubleValue();
        return c < e8 && MarketMath.calculateRsRank(series, baseline, i) < 25;
    }

    @Override
    public double calculateTP(double entryPrice) {
        return entryPrice - 1.50;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double stopMove = (ticker.equals("NVDA") || ticker.equals("TSLA")) ? 4.50 : 3.00;
        return entryPrice + stopMove;
    }
}