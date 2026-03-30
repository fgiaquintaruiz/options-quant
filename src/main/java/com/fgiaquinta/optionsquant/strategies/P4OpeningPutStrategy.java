package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.GapAnalyzer;
import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import org.ta4j.core.BarSeries;

public class P4OpeningPutStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public P4OpeningPutStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        // This strategy operates primarily on the 15-Minute opening timeframe
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int idx15m = series15m.getEndIndex();
        if (idx15m < 1) return false;

        // =========================================================================
        // RULE 1: Previous Low Volatility (Squeeze on yesterday's last 15m bar)
        // =========================================================================
        double prevBandWidth = VolatilityAnalyzer.getBollingerBandWidthPct(series15m, idx15m - 1, 20, 2.0);
        if (prevBandWidth > 1.2) return false;

        // =========================================================================
        // RULE 2: Extreme Gap UP (Price jumps up > 1.5% from yesterday's close)
        // =========================================================================
        double gapPct = GapAnalyzer.getGapPercentage(series15m, idx15m);
        if (gapPct < 1.5) return false;

        // =========================================================================
        // RULE 3: Bearish Reversal on Opening (Selling pressure)
        // =========================================================================
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();

        // The first 15m candle must be red (bearish) indicating the gap is being rejected
        boolean isBearishReversal = currentClose15m < currentOpen15m;

        return isBearishReversal;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // Target: Aiming for the "Gap Fill" downwards (return to yesterday's levels)
        return Math.round((entryPrice * 0.96) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Stop loss set above the entry to protect against further upward momentum
        return Math.round((entryPrice * 1.02) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "p4_opening_put";
    }
}