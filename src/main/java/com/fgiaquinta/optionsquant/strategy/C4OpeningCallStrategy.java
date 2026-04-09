package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;
import java.time.ZoneId;

public class C4OpeningCallStrategy implements TradingStrategy {

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // =========================================================================
        // RULE 3: THE SNIPER (Only trade from 9:30 to 9:35 AM)
        // =========================================================================
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 30 || nyTime.getMinute() > 35) {
            return false;
        }

        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);
        BarSeries series5m = data.getSeries(TimeFrame.MIN_5);

        if (series15m == null || series5m == null || series15m.isEmpty() || series5m.isEmpty()) return false;

        int idx15m = data.getIndexForTime(series15m, currentTime);
        int idx5m = data.getIndexForTime(series5m, currentTime);
        if (idx15m < 20 || idx5m < 1) return false;

        // =========================================================================
        // RULE 1: LATERAL TREND (Previous day)
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        double prevSma = sma20_15m.getValue(idx15m - 1).doubleValue();
        double prevSd = sd15m.getValue(idx15m - 1).doubleValue();
        double prevLowerBand = prevSma - (prevSd * 2);
        double prevUpperBand = prevSma + (prevSd * 2);

        // Relax the lateral channel a bit (max band width of 2%)
        double bandWidthPct = (prevUpperBand - prevLowerBand) / prevSma;
        if (bandWidthPct > 0.02) return false;

        // =========================================================================
        // RULE 2: THE GOLDEN GAP ZONE (GAP DOWN)
        // =========================================================================
        double openToday = series5m.getBar(idx5m).getOpenPrice().doubleValue();
        double closeYesterday = series5m.getBar(idx5m - 1).getClosePrice().doubleValue();

        // Did it open below yesterday's Lower Bollinger Band?
        boolean isExtremeGapDown = openToday < prevLowerBand;
        if (!isExtremeGapDown) return false;

        // GAP RANGE FILTER: Between -1.5% and -6.0%
        double gapPct = (openToday - closeYesterday) / closeYesterday;
        if (gapPct > -0.015 || gapPct < -0.06) return false;

        // =========================================================================
        // RULE 3: SIMPLE CONFIRMATION
        // =========================================================================
        double currentClose = series5m.getBar(idx5m).getClosePrice().doubleValue();

        // No wick filters. If it closes green, the rebound has begun.
        boolean isGreenCandle = currentClose > openToday;

        return isGreenCandle;
    }
}
