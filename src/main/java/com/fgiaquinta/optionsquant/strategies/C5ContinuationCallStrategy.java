package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.WordenAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class C5ContinuationCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C5ContinuationCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        // RULE 1: Moderate Gap Up (Strength indicator)
        double gapPct = GapAnalyzer.getGapPercentage(series1h, index);
        if (gapPct < 0.4 || gapPct > 1.8) return false;

        // RULE 2: Worden Stochastic Strength (Price is in the upper percentile)
        double wStoc = WordenAnalyzer.getWordenStochastic(series1h, index, 12, 3);
        if (wStoc < 60) return false;

        // RULE 3: 15m Confirmation (Momentum alignment)
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int idx15m = series15m.getEndIndex();
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20 = new SMAIndicator(close15m, 20);

        double currentPrice15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double sma20Val = sma20.getValue(idx15m).doubleValue();

        return currentPrice15m > sma20Val;
    }

    @Override
    public double calculateTP(double entryPrice) {
        return Math.round((entryPrice * 1.045) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        return Math.round((entryPrice * 0.975) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "c5_continuation_put";
    }
}