package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class P3BouncePutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P3BouncePutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        RSIIndicator rsi = new RSIIndicator(new ClosePriceIndicator(series1h), 14);

        // REGLA: RSI por encima de 70 (Sobrecompra) y vela actual bajista
        double rsiVal = rsi.getValue(index).doubleValue();
        double open = series1h.getBar(index).getOpenPrice().doubleValue();
        double close = series1h.getBar(index).getClosePrice().doubleValue();

        return rsiVal > 70 && close < open;
    }

    @Override
    public double calculateTP(double entryPrice) {
        double tpMult = ConfigLoader.getConfig().getDouble("bounce", "tp");
        return Math.round((entryPrice * (1 - tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double slMult = ConfigLoader.getConfig().getDouble("bounce", "sl");
        return Math.round((entryPrice * (1 + slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "p3_bounce_put";
    }
}