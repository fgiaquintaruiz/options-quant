package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;

public class P1SqueezePutStrategy implements TradingStrategy {

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1h == null || series15m == null || series1h.isEmpty() || series15m.isEmpty()) return false;

        int idx1h = data.getIndexForTime(series1h, currentTime);
        int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1h < 200 || idx15m < 20) return false;

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);

        // =========================================================================
        // RULE 1 and 2: LATERAL CHANNEL AND INTERLACED AVERAGES (10 DAYS / ~70 BARS)
        // =========================================================================
        SMAIndicator sma20 = new SMAIndicator(close1h, 20);
        SMAIndicator sma40 = new SMAIndicator(close1h, 40);
        SMAIndicator sma100 = new SMAIndicator(close1h, 100);
        SMAIndicator sma200 = new SMAIndicator(close1h, 200);

        int prevIdx = idx1h - 1;
        double s20 = sma20.getValue(prevIdx).doubleValue();
        double s40 = sma40.getValue(prevIdx).doubleValue();
        double s100 = sma100.getValue(prevIdx).doubleValue();
        double s200 = sma200.getValue(prevIdx).doubleValue();

        double maxSma = Math.max(Math.max(s20, s40), Math.max(s100, s200));
        double minSma = Math.min(Math.min(s20, s40), Math.min(s100, s200));

        if ((maxSma - minSma) / minSma > 0.04) return false;

        // Find the floor of the channel over last 10 days (70 bars)
        double minPriceLast10Days = Double.MAX_VALUE;
        for (int i = 1; i <= 70; i++) {
            double low = series1h.getBar(idx1h - i).getLowPrice().doubleValue();
            if (low < minPriceLast10Days) minPriceLast10Days = low;
        }

        // =========================================================================
        // RULE 3: THE BEARISH BREAKOUT
        // =========================================================================
        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double currentOpen1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();

        // Breaks the floor with a red candle
        boolean isBreakoutDown = currentClose1h < minPriceLast10Days && currentClose1h < currentOpen1h;
        if (!isBreakoutDown) return false;

        // =========================================================================
        // RULE 4: HIGH VOLATILITY CONFIRMATION ON 15-MIN BOLLINGER BAND
        // Book: "confirmacion con vela final bajista en Bollinger Bands en periodo de 15 minutos con alta volatilidad"
        // =========================================================================
        BollingerBandsUtil bb15m = new BollingerBandsUtil(series15m, 20);
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();

        // Pushing the lower band downward
        boolean isRidingLowerBand = bb15m.isRidingLowerBand(idx15m, 0.005); // Within 0.5% of lower band

        return isRidingLowerBand;
    }
}
