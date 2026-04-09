package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.time.ZonedDateTime;
import java.time.ZoneId;

public class C1SqueezeCallStrategy implements TradingStrategy {

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1h == null || series15m == null || series1h.isEmpty() || series15m.isEmpty()) return false;

        int idx1h = data.getIndexForTime(series1h, currentTime);
        int idx15m = data.getIndexForTime(series15m, currentTime);

        // Need 200 hours of history for SMA 200
        if (idx1h < 200 || idx15m < 20) return false;

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);

        // =========================================================================
        // RULE 1 and 2: LATERAL CHANNEL AND INTERLACED AVERAGES (10 DAYS / ~70 BARS)
        // =========================================================================
        SMAIndicator sma20 = new SMAIndicator(close1h, 20);
        SMAIndicator sma40 = new SMAIndicator(close1h, 40);
        SMAIndicator sma100 = new SMAIndicator(close1h, 100);
        SMAIndicator sma200 = new SMAIndicator(close1h, 200);

        // Evaluate state right BEFORE the current bar (the breakout bar)
        int prevIdx = idx1h - 1;
        double s20 = sma20.getValue(prevIdx).doubleValue();
        double s40 = sma40.getValue(prevIdx).doubleValue();
        double s100 = sma100.getValue(prevIdx).doubleValue();
        double s200 = sma200.getValue(prevIdx).doubleValue();

        // Calculate how tightly packed the 4 averages are (difference between max and min)
        double maxSma = Math.max(Math.max(s20, s40), Math.max(s100, s200));
        double minSma = Math.min(Math.min(s20, s40), Math.min(s100, s200));

        // If averages are separated by more than 4%, they are NOT laterally interlaced
        if ((maxSma - minSma) / minSma > 0.04) return false;

        // Find the ceiling of the channel over last 10 days (70 bars)
        double maxPriceLast10Days = 0;
        for (int i = 1; i <= 70; i++) {
            double high = series1h.getBar(idx1h - i).getHighPrice().doubleValue();
            if (high > maxPriceLast10Days) maxPriceLast10Days = high;
        }

        // =========================================================================
        // RULE 3: THE BREAKOUT (Jump or Extreme Candle)
        // =========================================================================
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double currentOpen1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();

        // Current price must forcefully break the 10-day ceiling
        boolean isBreakoutUp = currentClose1h > maxPriceLast10Days && currentClose1h > currentOpen1h;
        if (!isBreakoutUp) return false;

        // =========================================================================
        // RULE 4: HIGH VOLATILITY CONFIRMATION ON 15-MIN BOLLINGER BAND
        // =========================================================================
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);
        StandardDeviationIndicator sd15m = new StandardDeviationIndicator(close15m, 20);

        double currentSma15m = sma20_15m.getValue(idx15m).doubleValue();
        double currentSd15m = sd15m.getValue(idx15m).doubleValue();
        double upperBand15m = currentSma15m + (currentSd15m * 2);

        double currentClose15m = close15m.getValue(idx15m).doubleValue();

        // 15m candle must be "riding" the upper band (pushing volatility)
        boolean isRidingUpperBand = currentClose15m >= (upperBand15m * 0.995);

        return isRidingUpperBand;
    }
}
