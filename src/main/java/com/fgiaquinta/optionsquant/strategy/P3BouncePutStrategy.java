package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

public class P3BouncePutStrategy implements TradingStrategy {

    // ANTI-MACHINE-GUN: 2-hour cooldown
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        // =========================================================================
        // RULE 0.1: OPENING FILTER (Don't operate P3 in the first 30 min)
        // =========================================================================
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
        if (nyTime.getHour() < 10) {
            return false;
        }

        // =========================================================================
        // RULE 0.3: 2-HOUR COOLDOWN (Re-entry blocker)
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
        // RULE 1: Main Bearish Trend (1 Day) + Bullish BB context on 1H (per book)
        // Book: "Debemos encontrarnos en una tendencia claramente alcista en Bollinger en la temporalidad hora"
        // =========================================================================
        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        boolean isDowntrend = close1D.getValue(idx1D - 1).isLessThan(close1D.getValue(idx1D - 2));
        if (!isDowntrend) return false;

        // Book requirement: Verify price was in bullish BB context before rejection
        BollingerBandsUtil bb1h = new BollingerBandsUtil(series1h, 20);
        boolean wasInBullishBBContext = bb1h.brokeAboveUpperBand(idx1h - 1, 5); // Price touched/broke upper band recently

        // =========================================================================
        // RULE 2: Pullback (Bounce) to SMA20 on 1 Hour
        // =========================================================================
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        HighPriceIndicator high1h = new HighPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        double currentHigh1h = high1h.getValue(idx1h).doubleValue();
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double sma20Val1h = sma20_1h.getValue(idx1h).doubleValue();

        boolean touchedResistance = currentHigh1h >= (sma20Val1h * 0.998);
        boolean rejectedResistance = currentClose1h < sma20Val1h;

        if (!touchedResistance || !rejectedResistance) {
            return false;
        }

        // =========================================================================
        // RULE 3: 15-Minute Reversal Confirmation
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        double sma20Val15m = sma20_15m.getValue(idx15m).doubleValue();

        boolean confirmedDowntrend15m = currentPrice15m < sma20Val15m;

        // Signal requires: 15m confirmation AND prior bullish BB context (book requirement)
        if (confirmedDowntrend15m && wasInBullishBBContext) {
            lastTriggerMap.put(ticker, currentTime);
            return true;
        }

        return false;
    }
}
