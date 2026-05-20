package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.ConditionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.indicators.helpers.OpenPriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C6 - REVERSIÓN ALCISTA (Reversal Call) — sin referencia libro.
 *
 * Strategy not documented in the course author's book. Labels and logic derived from the code.
 *
 * EVALUATION STEPS (derived from code):
 * 1. Tendencia bajista previa en 1H  — 3 prior 1H closes below SMA20
 * 2. Ruptura alcista de SMA20        — current close crosses above SMA20 with bullish candle
 * 3. Cierre en 35% superior          — candle closes in top 35% of range, volume &ge; 90% avg
 * 4. Confirmación alcista en 15m     — price above and SMA20 rising on 15m
 *
 * Cooldown: 2 hours between triggers per ticker. Excluded: 9h NY.
 */
@Slf4j
public class C6ReversalCallStrategy implements TradingStrategy, TimeframeRequirements {
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Evaluates the C6 Reversal Call strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through four sequential conditions derived from the code logic
     * (no reference book reference — hence "(sin referencia libro)" labels):
     * <ol>
     *   <li>Tendencia bajista previa en 1H — 3 prior 1H closes below SMA20</li>
     *   <li>Ruptura alcista de SMA20 en 1H con vela verde — close crosses above SMA20 with bullish candle</li>
     *   <li>Cierre en 35% superior y volumen suficiente — close in top 35% of range, volume &ge; 90% avg</li>
     *   <li>Confirmación de tendencia alcista en 15m — price above rising SMA20 on 15m</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [C6] <ticker> @ <time> — Paso X/4 "<description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all four conditions are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) return false;

        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) return false;

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

        // ---- Pre-compute all values needed by conditions ----

        final ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        final OpenPriceIndicator open1h = new OpenPriceIndicator(series1h);
        final HighPriceIndicator high1h = new HighPriceIndicator(series1h);
        final LowPriceIndicator low1h = new LowPriceIndicator(series1h);
        final SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        // Paso 1: prior downtrend — 3 bars below SMA20
        boolean wasClearDowntrend = true;
        int barsAboveSma = 0;
        for (int i = 1; i <= 3; i++) {
            if (close1h.getValue(idx1h - i).doubleValue() >= sma20_1h.getValue(idx1h - i).doubleValue()) {
                wasClearDowntrend = false;
                barsAboveSma++;
            }
        }
        final boolean capturedDowntrend = wasClearDowntrend;
        final int capturedBarsAboveSma = barsAboveSma;

        // Paso 2 & 3: breakout values
        final double currentClose1h = close1h.getValue(idx1h).doubleValue();
        final double currentOpen1h  = open1h.getValue(idx1h).doubleValue();
        final double currentHigh1h  = high1h.getValue(idx1h).doubleValue();
        final double currentLow1h   = low1h.getValue(idx1h).doubleValue();
        final double currentSma1h   = sma20_1h.getValue(idx1h).doubleValue();

        final boolean crossedAboveSma  = currentClose1h > currentSma1h;
        final boolean isBullishCandle  = currentClose1h > currentOpen1h;
        final double candleRange       = currentHigh1h - currentLow1h;
        final boolean closedNearHigh   = candleRange > 0 && (currentHigh1h - currentClose1h) <= (candleRange * 0.35);

        final VolumeIndicator vol1h   = new VolumeIndicator(series1h);
        final SMAIndicator avgVol1h   = new SMAIndicator(vol1h, 10);
        final double currentVol = vol1h.getValue(idx1h).doubleValue();
        final double avgVol     = avgVol1h.getValue(idx1h).doubleValue();
        final boolean hasVolume = currentVol >= (avgVol * 0.90);

        // Paso 4: 15m uptrend confirmation
        final ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        final SMAIndicator sma20_15m       = new SMAIndicator(close15m, 20);
        final double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        final double currentSma15m   = sma20_15m.getValue(idx15m).doubleValue();
        final double prevSma15m      = sma20_15m.getValue(idx15m - 1).doubleValue();
        final boolean isUptrend15m   = (currentPrice15m > currentSma15m) && (currentSma15m > prevSma15m);

        // ---- Four-step condition array (derived from code logic — sin referencia libro) ----

        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test() { return capturedDowntrend; }
                @Override public String label() { return "Tendencia bajista previa en 1H (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("bars-above-sma20=%d/3 (need 0); wasBelowSma=%b",
                            capturedBarsAboveSma, capturedDowntrend);
                }
            },
            new Condition() {
                @Override public boolean test() { return crossedAboveSma && isBullishCandle; }
                @Override public String label() { return "Ruptura alcista de SMA20 en 1H con vela verde (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("close %.4f > SMA20 %.4f=%b; bullish(close>open)=%b",
                            currentClose1h, currentSma1h, crossedAboveSma, isBullishCandle);
                }
            },
            new Condition() {
                @Override public boolean test() { return closedNearHigh && hasVolume; }
                @Override public String label() { return "Cierre en 35% superior y volumen suficiente (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("closedNearHigh=%b (%.1f%% from top); vol %.0f>=90%%avg %.0f=%b",
                            closedNearHigh,
                            candleRange > 0 ? ((currentHigh1h - currentClose1h) / candleRange * 100) : 0,
                            currentVol, avgVol * 0.90, hasVolume);
                }
            },
            new Condition() {
                @Override public boolean test() { return isUptrend15m; }
                @Override public String label() { return "Confirmación de tendencia alcista en 15m (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("15m price %.4f>SMA %.4f && SMA>prevSMA %.4f=%b",
                            currentPrice15m, currentSma15m, prevSma15m, isUptrend15m);
                }
            }
        );

        if (!ConditionEvaluator.evaluate("[C6]", ticker, nyTime, conditions, log)) {
            return false;
        }

        lastTriggerMap.put(ticker, currentTime);
        return true;
    }
}
