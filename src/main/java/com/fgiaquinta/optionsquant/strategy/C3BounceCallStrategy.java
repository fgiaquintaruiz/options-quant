package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class C3BounceCallStrategy implements TradingStrategy {

    // ANTI-MACHINE-GUN: 2-hour cooldown
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        // =========================================================================
        // RULE 0.1: OPENING FILTER (Block 9 AM NY)
        // =========================================================================
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) {
            return false;
        }

        // =========================================================================
        // RULE 0.2: 2-HOUR COOLDOWN (Re-entry blocker)
        // =========================================================================
        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) {
            return false;
        }

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

        // =========================================================================
        // RULE 1: Main Uptrend (1 Day)
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        // Require yesterday's close > day before yesterday's close
        boolean isUptrend = close1D.getValue(idx1D - 1).isGreaterThan(close1D.getValue(idx1D - 2));
        if (!isUptrend) return false;

        // =========================================================================
        // RULE 2: Pullback (Bounce) to SMA20 support on 1 Hour
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        LowPriceIndicator low1h = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        double currentLow1h = low1h.getValue(idx1h).doubleValue();
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double sma20Val1h = sma20_1h.getValue(idx1h).doubleValue();

        // Touched support (0.2% margin above)
        boolean touchedSupport = currentLow1h <= (sma20Val1h * 1.002);
        // Rejected falling and body closed above support
        boolean rejectedSupport = currentClose1h > sma20Val1h;

        if (!touchedSupport || !rejectedSupport) {
            return false;
        }

        // =========================================================================
        // RULE 3: 15-Minute Reversal Confirmation
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double sma20Val15m = sma20_15m.getValue(idx15m).doubleValue();

        // On 15m, price has crossed and stays above SMA20
        boolean confirmedUptrend15m = currentPrice15m > sma20Val15m;

        if (confirmedUptrend15m) {
            lastTriggerMap.put(ticker, currentTime);
            return true;
        }

        return false;
    }
}
