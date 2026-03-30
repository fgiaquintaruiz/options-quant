package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.analyzers.TrendAnalyzer;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
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
        String ticker = series1h.getName().split("_")[0];

        double currentClose = series1h.getBar(index).getClosePrice().doubleValue();
        double currentOpen = series1h.getBar(index).getOpenPrice().doubleValue();

        // Require a strong bearish (red) candle
        boolean isBearishCandle = currentClose < currentOpen;
        if (!isBearishCandle) return false;

        // =========================================================================
        // RULES 1 & 2: Bullish Trend Line (Support) Breakout
        // =========================================================================
        // Look for valleys in the last 50 candles (approx. 1 week of market data)
        double supportLineValue = TrendAnalyzer.getBullishTrendLineValue(series1h, index, 50);

        // If a valid line couldn't be drawn (e.g., flat market with no clear valleys), abort
        if (supportLineValue == -1) return false;

        // The closing price must break the projected support line downwards
        if (currentClose >= supportLineValue) return false;

        // =========================================================================
        // RULE 3: Moving Average (SMA20) Confirmation on 1 Hour
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);
        double currentSma20 = sma20_1h.getValue(index).doubleValue();

        // The price must close below the SMA20
        if (currentClose >= currentSma20) return false;

        // =========================================================================
        // RULE 4: Lower Timeframe Validation (15 Minutes)
        // =========================================================================
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int last15mIdx = series15m.getEndIndex();
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double close15mVal = close15m.getValue(last15mIdx).doubleValue();
        double open15mVal = series15m.getBar(last15mIdx).getOpenPrice().doubleValue();
        double sma20_15mVal = sma20_15m.getValue(last15mIdx).doubleValue();

        // On the 15m chart, the trend must be clearly bearish:
        // Red candle AND price below its own SMA20
        boolean isBearish15m = (close15mVal < open15mVal) && (close15mVal < sma20_15mVal);

        return isBearish15m;
    }

    @Override
    public double calculateTP(double entryPrice) {
        // Trend reversal target: aiming for a 6% drop in the underlying asset
        return Math.round((entryPrice * 0.94) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        // Stop loss: maximum 3% risk against the position (price goes up)
        return Math.round((entryPrice * 1.03) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "p2_trend_put";
    }
}