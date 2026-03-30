package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.WordenAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class P5ContinuationPutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P5ContinuationPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        // REGLA 1: Filtrado de Gap Down (Valores negativos desde config.yaml)
        double minGap = ConfigLoader.getConfig().getParam("continuation", "putMinGap");
        double maxGap = ConfigLoader.getConfig().getParam("continuation", "putMaxGap");

        double gapPct = GapAnalyzer.getGapPercentage(series1h, index);

        // Para un PUT, el gap suele ser entre -0.4 y -1.8
        if (gapPct > minGap || gapPct < maxGap) return false;

        // REGLA 2: Worden Stochastic (Buscamos debilidad, ej: percentil < 45)
        double threshold = ConfigLoader.getConfig().getParam("continuation", "putWordenThreshold");
        double wStoc = WordenAnalyzer.getWordenStochastic(series1h, index, 12, 3);

        if (wStoc > threshold) return false;

        // REGLA 3: Confirmación en 15m (Precio < SMA20)
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int idx15m = series15m.getEndIndex();
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20 = new SMAIndicator(close15m, 20);

        double currentPrice15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double sma20Val = sma20.getValue(idx15m).doubleValue();

        return currentPrice15m < sma20Val;
    }

    @Override
    public double calculateTP(double entryPrice) {
        double tpMult = ConfigLoader.getConfig().getParam("continuation", "tp");
        return Math.round((entryPrice * (1 - tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double slMult = ConfigLoader.getConfig().getParam("continuation", "sl");
        return Math.round((entryPrice * (1 + slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "P5_CONTINUATION_PUT";
    }
}