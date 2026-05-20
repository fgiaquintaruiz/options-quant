package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.indicator.WordenStochasticIndicator;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import com.fgiaquinta.optionsquant.strategy.utils.ConditionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * C5 - EFECTO IMÁN (Magnet Effect) - CALL version
 *
 * Bearish trend + gap down + Bollinger breakout below + Worden Stochastic confirmation.
 *
 * REQUIREMENTS (from the course author's book, libro 7/8):
 * 1. "Tendencia clara llevando varios días"                         — bearish daily trend (2+ red candles)
 * 2. "Apertura con fuerte salto, precio alejado de SMA20"          — gap down + >3% below 1H SMA20
 * 3. "Primera vela 15m completamente fuera del Bollinger"          — first 15m candle fully below lower BB
 * 4. "Vela cruza línea roja del Worden Stochastics → entrada"      — reversal up + Worden / volume confirmation
 *
 * Time window: 9:45 AM - 9:55 AM NY (after the first 15m candle closes)
 */
@Slf4j
public class C5ContinuationCallStrategy implements TradingStrategy, TimeframeRequirements {

    private final WordenStochasticIndicator wordenStochastic;

    /**
     * Constructor with WordenStochasticIndicator for confirmation.
     */
    public C5ContinuationCallStrategy(WordenStochasticIndicator wordenStochastic) {
        this.wordenStochastic = wordenStochastic;
    }

    /**
     * Default constructor using volume surge as fallback (legacy behavior).
     */
    public C5ContinuationCallStrategy() {
        this.wordenStochastic = null;
    }

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Evaluates the C5 Continuation Call (Efecto Imán) strategy against the provided market data
     * at {@code currentTime}.
     *
     * <p>Evaluation window: 9:45 AM – 9:55 AM NY (right after the first 15m candle closes).
     * Outside this window the method returns {@code false} immediately.
     *
     * <p>The evaluation proceeds through four sequential book steps (short-circuit on first failure):
     * <ol>
     *   <li>Tendencia clara llevando varios días — bearish daily trend (3 prior descending closes)</li>
     *   <li>Apertura con fuerte salto, precio alejado de SMA20 — gap down + open &gt;3% below 1H SMA20</li>
     *   <li>Primera vela 15m completamente fuera del Bollinger — first 15m candle high below lower BB</li>
     *   <li>Vela cruza línea roja del Worden Stochastics → entrada — reversal up + Worden/volume confirmation</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [C5] <ticker> @ <time> — Paso X/4 "<libro description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all four steps are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // =========================================================================
        // RULE 0: THE SNIPER (Evaluate right after the 1st 15m candle)
        // Window: 9:45 AM to 9:55 AM
        // =========================================================================
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 45 || nyTime.getMinute() > 55) {
            return false;
        }

        BarSeries series1D = data.getSeries(TimeFrame.DAY_1);
        BarSeries series1h = data.getSeries(TimeFrame.HOUR_1);
        BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null ||
                series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) return false;

        int idx1D = data.getIndexForTime(series1D, currentTime);
        int idx1h = data.getIndexForTime(series1h, currentTime);
        int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1D < 3 || idx1h < 20 || idx15m < 20) return false;

        // =========================================================================
        // Pre-compute all values needed by conditions (capture for lambda closures)
        // =========================================================================
        final ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        final double prevClose1D  = close1D.getValue(idx1D - 1).doubleValue();
        final double prev2Close1D = close1D.getValue(idx1D - 2).doubleValue();
        final double prev3Close1D = close1D.getValue(idx1D - 3).doubleValue();

        final ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        final SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);
        final double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        final int firstCandle15mIdx = idx15m - 1; // The 9:30–9:45 candle
        final double first15mOpen = series15m.getBar(firstCandle15mIdx).getOpenPrice().doubleValue();
        final double first15mHigh = series15m.getBar(firstCandle15mIdx).getHighPrice().doubleValue();

        final BollingerBandsUtil bb = new BollingerBandsUtil(series15m, 20);
        final double lowerBand15m = bb.getLower(firstCandle15mIdx - 1);

        final double currentPrice = series15m.getBar(idx15m).getClosePrice().doubleValue();
        final double currentOpen  = series15m.getBar(idx15m).getOpenPrice().doubleValue();
        final boolean isReversingUp = currentPrice > currentOpen;

        final boolean confirmation;
        if (wordenStochastic != null) {
            double currentWorden = wordenStochastic.getValue(idx15m).doubleValue();
            double prevWorden    = wordenStochastic.getValue(idx15m - 1).doubleValue();
            confirmation = prevWorden <= 20 && currentWorden > 20;
        } else {
            VolumeIndicator vol15m = new VolumeIndicator(series15m);
            SMAIndicator avgVol15m = new SMAIndicator(vol15m, 10);
            double firstCandleVol = vol15m.getValue(firstCandle15mIdx).doubleValue();
            double avgVol = avgVol15m.getValue(firstCandle15mIdx - 1).doubleValue();
            confirmation = firstCandleVol > (avgVol * 1.5);
        }

        // =========================================================================
        // CONDITION PIPELINE — 4 reference book steps
        // =========================================================================
        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test()   { return prevClose1D < prev2Close1D && prev2Close1D < prev3Close1D; }
                @Override public String label()   { return "Tendencia clara llevando varios días"; }
                @Override public String value()   { return String.format("close[-1]=%.2f < close[-2]=%.2f < close[-3]=%.2f",
                        prevClose1D, prev2Close1D, prev3Close1D); }
            },
            new Condition() {
                @Override public boolean test()   { return first15mOpen < prevClose1D && first15mOpen < currentSma1h * 0.97; }
                @Override public String label()   { return "Apertura con fuerte salto, precio alejado de SMA20"; }
                @Override public String value()   { return String.format("open=%.2f < prevClose=%.2f; open=%.2f < SMA20*0.97=%.2f",
                        first15mOpen, prevClose1D, first15mOpen, currentSma1h * 0.97); }
            },
            new Condition() {
                @Override public boolean test()   { return first15mHigh < lowerBand15m; }
                @Override public String label()   { return "Primera vela 15m completamente fuera del Bollinger"; }
                @Override public String value()   { return String.format("high=%.2f < lowerBB=%.2f", first15mHigh, lowerBand15m); }
            },
            new Condition() {
                @Override public boolean test()   { return isReversingUp && confirmation; }
                @Override public String label()   { return "Vela cruza línea roja del Worden Stochastics → entrada"; }
                @Override public String value()   { return String.format("reversingUp=%b confirmation=%b (close=%.2f open=%.2f)",
                        isReversingUp, confirmation, currentPrice, currentOpen); }
            }
        );

        return ConditionEvaluator.evaluate("[C5]", ticker, nyTime, conditions, log);
    }
}
