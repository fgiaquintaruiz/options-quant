package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.TrendAnalyzer;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class P6ReversalPutStrategy implements TradingStrategy {
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) return false;

        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) return false;

        BarSeries series1D = data.getSeries(TimeFrame.DAY_1);
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) {
            return false;
        }

        int idx1D = data.getIndexForTime(series1D, currentTime);
        int idx1h = data.getIndexForTime(series1h, currentTime);
        int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1D < 20 || idx1h < 20 || idx15m < 20) return false;

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        OpenPriceIndicator open1h = new OpenPriceIndicator(series1h);
        HighPriceIndicator high1h = new HighPriceIndicator(series1h);
        LowPriceIndicator low1h = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        // =========================================================================
        // RULE 1: PREVIOUS UPTREND ("Being above" filter)
        // Price must have closed ABOVE SMA20 for at least the 3 prior hours.
        // =========================================================================
        boolean wasClearUptrend = true;
        for (int i = 1; i <= 3; i++) {
            if (close1h.getValue(idx1h - i).doubleValue() <= sma20_1h.getValue(idx1h - i).doubleValue()) {
                wasClearUptrend = false;
                break;
            }
        }
        if (!wasClearUptrend) return false;

        // RULE 1b: STRUCTURED UPTREND CONFIRMATION (TrendAnalyzer)
        double bullishTrendLine = TrendAnalyzer.getBullishTrendLineValue(series1h, idx1h - 1, 30);
        if (bullishTrendLine > 0) {
            double prevClose = close1h.getValue(idx1h - 1).doubleValue();
            if (prevClose <= bullishTrendLine) return false;
        }

        // =========================================================================
        // RULES 2 and 3: SMA20 BREAKDOWN (PUT)
        // =========================================================================
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double currentOpen1h = open1h.getValue(idx1h).doubleValue();
        double currentHigh1h = high1h.getValue(idx1h).doubleValue();
        double currentLow1h = low1h.getValue(idx1h).doubleValue();
        double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        // Crossed downward (current price is below the average)
        boolean crossedBelowSma = currentClose1h < currentSma1h;
        // Red candle (close lower than open)
        boolean isBearishCandle = currentClose1h < currentOpen1h;

        // WICK FILTER: Candle must close in its bottom 35% (no long lower wicks)
        double candleRange = currentHigh1h - currentLow1h;
        boolean closedNearLow = (currentClose1h - currentLow1h) <= (candleRange * 0.35);

        if (!crossedBelowSma || !isBearishCandle || !closedNearLow) return false;

        // VOLUME FILTER: At least 90% of the average of the last 10 bars
        VolumeIndicator vol1h = new VolumeIndicator(series1h);
        SMAIndicator avgVol1h = new SMAIndicator(vol1h, 10);
        double currentVol = vol1h.getValue(idx1h).doubleValue();
        double avgVol = avgVol1h.getValue(idx1h).doubleValue();
        if (currentVol < (avgVol * 0.90)) return false;

        // =========================================================================
        // RULE 4: 15-MINUTE CONFIRMATION (Full bearish trend)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double currentSma15m = sma20_15m.getValue(idx15m).doubleValue();
        double prevSma15m = sma20_15m.getValue(idx15m - 1).doubleValue();

        // Price is below 15m SMA and the SMA is pointing down
        boolean isDowntrend15m = (currentPrice15m < currentSma15m) && (currentSma15m < prevSma15m);

        if (isDowntrend15m) {
            lastTriggerMap.put(ticker, currentTime);
            return true;
        }

        return false;
    }
}
