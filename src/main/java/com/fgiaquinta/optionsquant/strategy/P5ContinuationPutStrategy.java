package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.indicator.WordenStochasticIndicator;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import com.fgiaquinta.optionsquant.strategy.utils.ConditionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * P5 - EFECTO IMÁN (Magnet Effect) - PUT version
 *
 * Bullish trend + gap up + Bollinger breakout above + Worden Stochastic confirmation.
 *
 * REQUIREMENTS (from the course author's book, libro 7/8):
 * 1. "Tendencia alcista clara llevando varios días"                  — bullish daily trend (2+ green candles)
 * 2. "Apertura con fuerte caída, precio alejado de SMA20"           — gap up + >3% above 1H SMA20
 * 3. "Primera vela 15m completamente fuera del Bollinger superior"  — first 15m candle fully above upper BB
 * 4. "Vela cruza línea roja del Worden Stochastics → entrada"       — reversal down + Worden / volume confirmation
 *
 * Time window: 9:45 AM - 9:55 AM NY (after the first 15m candle closes)
 */
@Slf4j
@Component
public class P5ContinuationPutStrategy implements TradingStrategy, TimeframeRequirements {

    private final WordenStochasticIndicator wordenStochastic;

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Constructor with WordenStochasticIndicator for confirmation.
     */
    public P5ContinuationPutStrategy(WordenStochasticIndicator wordenStochastic) {
        this.wordenStochastic = wordenStochastic;
    }

    /**
     * Default constructor using volume surge as fallback (legacy behavior).
     */
    public P5ContinuationPutStrategy() {
        this.wordenStochastic = null;
    }

    /**
     * Evaluates the P5 Continuation Put (Efecto Imán) strategy against the provided market data
     * at {@code currentTime}.
     *
     * <p>Evaluation window: 9:45 AM – 9:55 AM NY (right after the first 15m candle closes).
     * Outside this window the method returns {@code false} immediately.
     *
     * <p>The evaluation proceeds through four sequential book steps (short-circuit on first failure):
     * <ol>
     *   <li>Tendencia alcista clara llevando varios días — bullish daily trend (3 prior ascending closes)</li>
     *   <li>Apertura con fuerte caída, precio alejado de SMA20 — gap up + open &gt;3% above 1H SMA20</li>
     *   <li>Primera vela 15m completamente fuera del Bollinger superior — first 15m candle low above upper BB</li>
     *   <li>Vela cruza línea roja del Worden Stochastics → entrada — reversal down + Worden/volume confirmation</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [P5] <ticker> @ <time> — Paso X/4 "<libro description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all four steps are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isCall() {
        return false;
    }

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        final ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // RULE 0: THE SNIPER — Window: 9:45 AM to 9:55 AM
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 45 || nyTime.getMinute() > 55) {
            return false;
        }

        final BarSeries series1D  = data.getSeries(TimeFrame.DAY_1);
        final BarSeries series1h  = data.getSeries(TimeFrame.HOUR_1);
        final BarSeries series15m = data.getSeries(TimeFrame.MIN_15);

        if (series1D == null || series1h == null || series15m == null
                || series1D.isEmpty() || series1h.isEmpty() || series15m.isEmpty()) return false;

        final int idx1D  = data.getIndexForTime(series1D, currentTime);
        final int idx1h  = data.getIndexForTime(series1h, currentTime);
        final int idx15m = data.getIndexForTime(series15m, currentTime);

        if (idx1D < 3 || idx1h < 20 || idx15m < 20) return false;

        // --- pre-compute all values needed by conditions ---
        final ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        final double prevClose1D  = close1D.getValue(idx1D - 1).doubleValue();
        final double prev2Close1D = close1D.getValue(idx1D - 2).doubleValue();
        final double prev3Close1D = close1D.getValue(idx1D - 3).doubleValue();

        final ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        final SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);
        final double currentSma1h = sma20_1h.getValue(idx1h).doubleValue();

        final int firstCandle15mIdx = idx15m - 1;
        final double first15mOpen = series15m.getBar(firstCandle15mIdx).getOpenPrice().doubleValue();
        final double first15mLow  = series15m.getBar(firstCandle15mIdx).getLowPrice().doubleValue();

        final BollingerBandsUtil bb = new BollingerBandsUtil(series15m, 20);
        final double upperBand15m = bb.getUpper(firstCandle15mIdx - 1);

        final double currentPrice = series15m.getBar(idx15m).getClosePrice().doubleValue();
        final double currentOpen  = series15m.getBar(idx15m).getOpenPrice().doubleValue();
        final boolean isReversingDown = currentPrice < currentOpen;

        final boolean confirmation;
        if (wordenStochastic != null) {
            final double currentWorden = wordenStochastic.getValue(idx15m).doubleValue();
            final double prevWorden    = wordenStochastic.getValue(idx15m - 1).doubleValue();
            confirmation = prevWorden >= 80 && currentWorden < 80;
        } else {
            final VolumeIndicator vol15m = new VolumeIndicator(series15m);
            final SMAIndicator avgVol15m = new SMAIndicator(vol15m, 10);
            final double firstCandleVol = vol15m.getValue(firstCandle15mIdx).doubleValue();
            final double avgVol = avgVol15m.getValue(firstCandle15mIdx - 1).doubleValue();
            confirmation = firstCandleVol > (avgVol * 1.5);
        }

        // --- 4 reference book steps ---
        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test()  { return prevClose1D > prev2Close1D && prev2Close1D > prev3Close1D; }
                @Override public String label()  { return "Tendencia alcista clara llevando varios días"; }
                @Override public String value()  { return String.format("close[-1]=%.2f > close[-2]=%.2f > close[-3]=%.2f",
                        prevClose1D, prev2Close1D, prev3Close1D); }
            },
            new Condition() {
                @Override public boolean test()  { return first15mOpen > prevClose1D && first15mOpen > currentSma1h * 1.03; }
                @Override public String label()  { return "Apertura con fuerte caída, precio alejado de SMA20"; }
                @Override public String value()  { return String.format("open=%.2f > prevClose=%.2f; open=%.2f > SMA20*1.03=%.2f",
                        first15mOpen, prevClose1D, first15mOpen, currentSma1h * 1.03); }
            },
            new Condition() {
                @Override public boolean test()  { return first15mLow > upperBand15m; }
                @Override public String label()  { return "Primera vela 15m completamente fuera del Bollinger superior"; }
                @Override public String value()  { return String.format("low=%.2f > upperBB=%.2f", first15mLow, upperBand15m); }
            },
            new Condition() {
                @Override public boolean test()  { return isReversingDown && confirmation; }
                @Override public String label()  { return "Vela cruza línea roja del Worden Stochastics → entrada"; }
                @Override public String value()  { return String.format("reversingDown=%b confirmation=%b (close=%.2f open=%.2f)",
                        isReversingDown, confirmation, currentPrice, currentOpen); }
            }
        );

        return ConditionEvaluator.evaluate("[P5]", ticker, nyTime, conditions, log);
    }
}
