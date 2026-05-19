package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import com.fgiaquinta.optionsquant.strategy.utils.ChannelAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

@Slf4j

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
        double smaSpread = (maxSma - minSma) / minSma;

        double minPriceLast10Days = Double.MAX_VALUE;
        for (int i = 1; i <= 70; i++) {
            double low = series1h.getBar(idx1h - i).getLowPrice().doubleValue();
            if (low < minPriceLast10Days) minPriceLast10Days = low;
        }

        double currentClose1h = close1h.getValue(idx1h).doubleValue();
        double currentOpen1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();
        double breakoutThreshold = minPriceLast10Days * (1.0 - breakoutBufferPct);

        double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();
        double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        double bodyPct15m = (currentClose15m - currentOpen15m) / currentOpen15m;

        BollingerBandsUtil bb15m = new BollingerBandsUtil(series15m, 20);
        double bbWidthCurrent = bb15m.getWidthPercent(idx15m);
        double bbWidthAvg = computeBBWidthAvg(bb15m, idx15m, 20);
        double bbWidthMinRequired = bbWidthAvg * bbVolatilityThreshold;

        final double capturedMinPrice = minPriceLast10Days;

        List<Condition> conditions = List.of(
            new Condition() {
                public boolean test() { return smaSpread <= 0.04; }
                public String describe() { return String.format("SMA spread %.4f <= 0.04", smaSpread); }
            },
            new Condition() {
                public boolean test() { return ChannelAnalyzer.isSmaLateralChannel(series1h, prevIdx, 70, 4.0); }
                public String describe() { return String.format("ChannelAnalyzer lateral (70 bars, 4.0%% threshold)"); }
            },
            new Condition() {
                public boolean test() { return currentClose1h < breakoutThreshold && currentClose1h < currentOpen1h; }
                public String describe() { return String.format("bearish breakout close %.4f < threshold %.4f (floor %.4f - %.1f%%)", currentClose1h, breakoutThreshold, capturedMinPrice, breakoutBufferPct * 100); }
            },
            new Condition() {
                public boolean test() { return bodyPct15m <= -minBodyPct; }
                public String describe() { return String.format("15m body %.4f <= -%.4f (bearish body filter)", bodyPct15m, minBodyPct); }
            },
            new Condition() {
                public boolean test() { return bbWidthCurrent >= bbWidthMinRequired; }
                public String describe() { return String.format("BB width %.4f >= avg*threshold %.4f (volatility expansion)", bbWidthCurrent, bbWidthMinRequired); }
            },
            new Condition() {
                public boolean test() { return bb15m.isRidingLowerBand(idx15m, 0.005); }
                public String describe() { return String.format("15m riding lower BB (within 0.5%%)"); }
            }
        );

        for (Condition c : conditions) {
            if (!c.test()) {
                if (log.isDebugEnabled()) {
                    log.debug("[P1] {} @ {} — {} ❌ STOP", ticker, currentTime.toLocalTime(), c.describe());
                }
                return false;
            }
            if (log.isDebugEnabled()) {
                log.debug("[P1] {} @ {} — {} ✅", ticker, currentTime.toLocalTime(), c.describe());
            }
        }
        return true;
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
