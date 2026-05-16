package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import com.fgiaquinta.optionsquant.strategy.utils.ChannelAnalyzer;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;
import java.util.Set;

public class P1SqueezePutStrategy implements TradingStrategy, TimeframeRequirements {

    private static final double DEFAULT_BREAKOUT_BUFFER_PCT = 0.003;
    private static final double DEFAULT_MIN_BODY_PCT = 0.002;
    private static final double DEFAULT_BB_VOLATILITY_THRESHOLD = 1.1;

    private final double breakoutBufferPct;
    private final double minBodyPct;
    private final double bbVolatilityThreshold;

    public P1SqueezePutStrategy() {
        this(DEFAULT_BREAKOUT_BUFFER_PCT, DEFAULT_MIN_BODY_PCT, DEFAULT_BB_VOLATILITY_THRESHOLD);
    }

    public P1SqueezePutStrategy(double breakoutBufferPct) {
        this(breakoutBufferPct, DEFAULT_MIN_BODY_PCT, DEFAULT_BB_VOLATILITY_THRESHOLD);
    }

    public P1SqueezePutStrategy(double breakoutBufferPct, double minBodyPct) {
        this(breakoutBufferPct, minBodyPct, DEFAULT_BB_VOLATILITY_THRESHOLD);
    }

    public P1SqueezePutStrategy(double breakoutBufferPct, double minBodyPct, double bbVolatilityThreshold) {
        this.breakoutBufferPct = breakoutBufferPct;
        this.minBodyPct = minBodyPct;
        this.bbVolatilityThreshold = bbVolatilityThreshold;
    }

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1);
    }

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

        // RULE 1b: MULTI-BAR COMPRESSION CONFIRMATION (ChannelAnalyzer)
        if (!ChannelAnalyzer.isSmaLateralChannel(series1h, prevIdx, 70, 4.0)) return false;

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

        // Breaks the floor with a red candle — must clear the buffer to avoid false breakouts
        double breakoutThreshold = minPriceLast10Days * (1.0 - breakoutBufferPct);
        boolean isBreakoutDown = currentClose1h < breakoutThreshold && currentClose1h < currentOpen1h;
        if (!isBreakoutDown) return false;

        // =========================================================================
        // RULE 3b: MIN_15 BODY FILTER (bearish confirmation on 15-min timeframe)
        // Body filter moved to MIN_15 — HOUR_1 breakout direction is already verified above.
        // =========================================================================
        double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double bodyPct15m = (currentClose15m - currentOpen15m) / currentOpen15m;
        if (bodyPct15m > -minBodyPct) return false;

        // =========================================================================
        // RULE 4: HIGH VOLATILITY CONFIRMATION ON 15-MIN BOLLINGER BAND
        // Book: "confirmacion con vela final bajista en Bollinger Bands en periodo de 15 minutos con alta volatilidad"
        // =========================================================================
        BollingerBandsUtil bb15m = new BollingerBandsUtil(series15m, 20);

        // BB width must exceed the 20-bar average by the volatility threshold — confirms expansion, not squeeze
        double bbWidthCurrent = bb15m.getWidthPercent(idx15m);
        double bbWidthAvg = computeBBWidthAvg(bb15m, idx15m, 20);
        if (bbWidthCurrent < bbWidthAvg * bbVolatilityThreshold) return false;

        // Pushing the lower band downward
        boolean isRidingLowerBand = bb15m.isRidingLowerBand(idx15m, 0.005); // Within 0.5% of lower band

        return isRidingLowerBand;
    }

    private double computeBBWidthAvg(BollingerBandsUtil bb, int currentIndex, int lookback) {
        double sum = 0;
        int count = 0;
        for (int i = currentIndex - 1; i >= Math.max(0, currentIndex - lookback); i--) {
            sum += bb.getWidthPercent(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
