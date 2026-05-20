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

    /** Creates a strategy instance with all default thresholds. */
    public P1SqueezePutStrategy() {
        this(DEFAULT_BREAKOUT_BUFFER_PCT, DEFAULT_MIN_BODY_PCT, DEFAULT_BB_VOLATILITY_THRESHOLD);
    }

    /**
     * Creates a strategy instance with a custom breakout buffer.
     *
     * @param breakoutBufferPct fraction below the 10-day low required for a valid breakdown
     *                          (e.g. {@code 0.003} = 0.3%)
     */
    public P1SqueezePutStrategy(double breakoutBufferPct) {
        this(breakoutBufferPct, DEFAULT_MIN_BODY_PCT, DEFAULT_BB_VOLATILITY_THRESHOLD);
    }

    /**
     * Creates a strategy instance with custom breakout buffer and minimum body size.
     *
     * @param breakoutBufferPct fraction below the 10-day low required for a valid breakdown
     * @param minBodyPct        minimum bearish candle body as a fraction of open price
     *                          (e.g. {@code 0.002} = 0.2%)
     */
    public P1SqueezePutStrategy(double breakoutBufferPct, double minBodyPct) {
        this(breakoutBufferPct, minBodyPct, DEFAULT_BB_VOLATILITY_THRESHOLD);
    }

    /**
     * Creates a fully-configured strategy instance.
     *
     * @param breakoutBufferPct     fraction below the 10-day low required for a valid breakdown
     * @param minBodyPct            minimum bearish candle body as a fraction of open price
     * @param bbVolatilityThreshold multiplier applied to the 20-bar average BB width;
     *                              current width must exceed {@code avg * threshold} to confirm
     *                              Bollinger expansion (e.g. {@code 1.1} = 10% wider than average)
     */
    public P1SqueezePutStrategy(double breakoutBufferPct, double minBodyPct, double bbVolatilityThreshold) {
        this.breakoutBufferPct = breakoutBufferPct;
        this.minBodyPct = minBodyPct;
        this.bbVolatilityThreshold = bbVolatilityThreshold;
    }

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1);
    }

    /**
     * Evaluates the P1 Squeeze Put strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through six sequential conditions (short-circuit on first failure):
     * <ol>
     *   <li>SMA compression (20/40/100/200 spread &le; 4% of min SMA)</li>
     *   <li>1h lateral channel confirmed by {@link com.fgiaquinta.optionsquant.strategy.utils.ChannelAnalyzer}</li>
     *   <li>Bearish 1h breakout below the 10-day low with buffer</li>
     *   <li>Bearish 15m candle body &ge; {@code minBodyPct}</li>
     *   <li>Bollinger Band expansion on 15m ({@code current width >= avg * threshold})</li>
     *   <li>Price riding the lower Bollinger Band on 15m</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each condition emits a structured log line with step
     * counter, quoted label, computed value, and a ✅ / ❌ STOP marker.
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all six conditions are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        final BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        final BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1h == null || series15m == null || series1h.isEmpty() || series15m.isEmpty()) return false;

        final int idx1h = data.getIndexForTime(series1h, currentTime);
        final int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1h < 200 || idx15m < 20) return false;

        final ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);

        final SMAIndicator sma20 = new SMAIndicator(close1h, 20);
        final SMAIndicator sma40 = new SMAIndicator(close1h, 40);
        final SMAIndicator sma100 = new SMAIndicator(close1h, 100);
        final SMAIndicator sma200 = new SMAIndicator(close1h, 200);

        final int prevIdx = idx1h - 1;
        final double s20 = sma20.getValue(prevIdx).doubleValue();
        final double s40 = sma40.getValue(prevIdx).doubleValue();
        final double s100 = sma100.getValue(prevIdx).doubleValue();
        final double s200 = sma200.getValue(prevIdx).doubleValue();

        final double maxSma = Math.max(Math.max(s20, s40), Math.max(s100, s200));
        final double minSma = Math.min(Math.min(s20, s40), Math.min(s100, s200));
        final double smaSpread = (maxSma - minSma) / minSma;

        double minPriceLast10Days = Double.MAX_VALUE;
        for (int i = 1; i <= 70; i++) {
            final double low = series1h.getBar(idx1h - i).getLowPrice().doubleValue();
            if (low < minPriceLast10Days) minPriceLast10Days = low;
        }

        final double currentClose1h = close1h.getValue(idx1h).doubleValue();
        final double currentOpen1h = series1h.getBar(idx1h).getOpenPrice().doubleValue();
        final double breakoutThreshold = minPriceLast10Days * (1.0 - breakoutBufferPct);

        final double currentOpen15m = series15m.getBar(idx15m).getOpenPrice().doubleValue();
        final double currentClose15m = series15m.getBar(idx15m).getClosePrice().doubleValue();
        final double bodyPct15m = (currentClose15m - currentOpen15m) / currentOpen15m;

        final BollingerBandsUtil bb15m = new BollingerBandsUtil(series15m, 20);
        final double bbWidthCurrent = bb15m.getWidthPercent(idx15m);
        final double bbWidthAvg = computeBBWidthAvg(bb15m, idx15m, 20);
        final double bbWidthMinRequired = bbWidthAvg * bbVolatilityThreshold;

        final double capturedMinPrice = minPriceLast10Days;

        final List<Condition> conditions = List.of(
            new Condition() {
                public boolean test() { return smaSpread <= 0.04; }
                public String label() { return "Squeeze SMAs (sin referencia libro)"; }
                public String value() { return String.format("SMA spread %.4f <= 0.04", smaSpread); }
            },
            new Condition() {
                public boolean test() { return ChannelAnalyzer.isSmaLateralChannel(series1h, prevIdx, 70, 4.0); }
                public String label() { return "Canal lateral SMAs (sin referencia libro)"; }
                public String value() { return String.format("ChannelAnalyzer lateral (70 bars, 4.0%% threshold)"); }
            },
            new Condition() {
                public boolean test() { return currentClose1h < breakoutThreshold && currentClose1h < currentOpen1h; }
                public String label() { return "Breakout bajista con buffer (sin referencia libro)"; }
                public String value() { return String.format("close %.4f < threshold %.4f (floor %.4f - %.1f%%)", currentClose1h, breakoutThreshold, capturedMinPrice, breakoutBufferPct * 100); }
            },
            new Condition() {
                public boolean test() { return bodyPct15m <= -minBodyPct; }
                public String label() { return "Vela bajista en 15m (sin referencia libro)"; }
                public String value() { return String.format("15m body %.4f <= -%.4f", bodyPct15m, minBodyPct); }
            },
            new Condition() {
                public boolean test() { return bbWidthCurrent >= bbWidthMinRequired; }
                public String label() { return "Expansión Bollinger 15m (sin referencia libro)"; }
                public String value() { return String.format("BB width %.4f >= avg*threshold %.4f", bbWidthCurrent, bbWidthMinRequired); }
            },
            new Condition() {
                public boolean test() { return bb15m.isRidingLowerBand(idx15m, 0.005); }
                public String label() { return "Precio riding lower BB 15m (sin referencia libro)"; }
                public String value() { return "15m riding lower BB (within 0.5%)"; }
            }
        );

        final int total = conditions.size();
        for (int step = 0; step < total; step++) {
            final Condition c = conditions.get(step);
            if (log.isDebugEnabled()) {
                final String stepPrefix = String.format("[P1] %s @ %s — Paso %d/%d \"%s\" → %s",
                        ticker, currentTime.toLocalTime(), step + 1, total, c.label(), c.value());
                if (!c.test()) {
                    log.debug("{} ❌ STOP", stepPrefix);
                    return false;
                }
                log.debug("{} ✅", stepPrefix);
            } else if (!c.test()) {
                return false;
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
