package com.fgiaquinta.optionsquant.strategies;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

public class C3BounceCallStrategy implements TradingStrategy {
    private final IbkrService ibkrService;

    public C3BounceCallStrategy(IbkrService ibkrService) {
        this.ibkrService = ibkrService;
    }

    @Override
    public boolean isTriggered(int index, BarSeries series1h, BarSeries spySeries) {
        String ticker = series1h.getName().split("_")[0];

        double currentClose = series1h.getBar(index).getClosePrice().doubleValue();
        double currentOpen = series1h.getBar(index).getOpenPrice().doubleValue();
        double currentLow = series1h.getBar(index).getLowPrice().doubleValue();

        // =========================================================================
        // RULE 4: Entry (1 Hour Timeframe) - Must be a bullish confirmation candle
        // =========================================================================
        boolean isBullishCandle = currentClose > currentOpen;
        if (!isBullishCandle) return false;

        // =========================================================================
        // RULE 1: Context (1 Hour Timeframe) - Trend must be clearly bearish
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        // Price should be operating in the lower half of the Bollinger Bands (below SMA20)
        if (currentClose >= sma20_1h.getValue(index).doubleValue()) {
            return false;
        }

        // =========================================================================
        // RULE 2: Approach Support (Daily Timeframe) - Touching Daily SMA20
        // =========================================================================
        BarSeries series1d = ibkrService.getSeries(ticker, TimeFrame.DAY_1);
        if (series1d == null || series1d.isEmpty()) return false;

        int lastDailyIdx = series1d.getEndIndex();
        ClosePriceIndicator close1d = new ClosePriceIndicator(series1d);
        SMAIndicator sma20_1d = new SMAIndicator(close1d, 20);

        double dailySma20 = sma20_1d.getValue(lastDailyIdx).doubleValue();

        // Calculate proximity to the Daily SMA20 (e.g., within 0.8% margin of error)
        double distanceToDailySma20 = Math.abs(currentLow - dailySma20) / dailySma20;
        boolean touchesDailySupport = distanceToDailySma20 <= 0.008;

        // The price must touch it but respect it (current close must be ABOVE the daily SMA20)
        boolean respectsDailySupport = currentClose > dailySma20;

        if (!touchesDailySupport || !respectsDailySupport) return false;

        // =========================================================================
        // RULE 3: Verify Bounce (15 Minutes Timeframe)
        // =========================================================================
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        if (series15m == null || series15m.isEmpty()) return false;

        int last15mIdx = series15m.getEndIndex();
        double close15mVal = series15m.getBar(last15mIdx).getClosePrice().doubleValue();
        double open15mVal = series15m.getBar(last15mIdx).getOpenPrice().doubleValue();

        // In 15m, the price must confirm the bounce by showing upward momentum (green candle)
        // and strictly remaining above the Daily SMA20 support level.
        boolean isBouncingUp15m = (close15mVal > open15mVal) && (close15mVal > dailySma20);

        return isBouncingUp15m;
    }

    @Override
    public double calculateTP(double entryPrice) {
        double tpMult = ConfigLoader.getConfig().getParam("bounce", "tp"); // Cambiar categoría según estrategia
        return Math.round((entryPrice * (1 + tpMult)) * 100.0) / 100.0;
    }

    @Override
    public double calculateSL(double entryPrice, String ticker) {
        double slMult = ConfigLoader.getConfig().getParam("bounce", "sl"); // Cambiar categoría según estrategia
        return Math.round((entryPrice * (1 - slMult)) * 100.0) / 100.0;
    }

    @Override
    public String getName() {
        return "c3_bounce_call";
    }
}