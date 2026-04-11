package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;

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
        // Book: "confirmacion con vela final alcista en Bollinger Bands en periodo de 15 minutos con alta volatilidad"
        // =========================================================================
        BollingerBandsUtil bb15m = new BollingerBandsUtil(series15m, 20);
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();

        // 15m candle must be "riding" the upper band (pushing volatility)
        boolean isRidingUpperBand = bb15m.isRidingUpperBand(idx15m, 0.005); // Within 0.5% of upper band

        return isRidingUpperBand;
    }
}
