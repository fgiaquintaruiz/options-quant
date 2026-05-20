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
 * P6 - REVERSIÓN BAJISTA (Reversal Put) — sin referencia libro.
 *
 * Strategy not documented in the course author's book. Labels and logic derived from the code.
 *
 * EVALUATION STEPS (derived from code):
 * 1. Tendencia alcista previa en 1H  — 3 prior 1H closes above SMA20
 * 2. Ruptura bajista de SMA20        — current close crosses below SMA20 with bearish candle
 * 3. Cierre en tercio inferior       — candle closes in bottom 35% of range, volume &ge; 90% avg
 * 4. Confirmación bajista en 15m     — price below and SMA20 falling on 15m
 *
 * Cooldown: 2 hours between triggers per ticker. Excluded: 9h NY.
 */
@Slf4j
public class P6ReversalPutStrategy implements TradingStrategy, TimeframeRequirements {
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Evaluates the P6 Reversal Put strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through four sequential conditions derived from the code logic
     * (no reference book reference — hence "(sin referencia libro)" labels):
     * <ol>
     *   <li>Tendencia alcista previa en 1H — 3 prior 1H closes above SMA20</li>
     *   <li>Ruptura bajista de SMA20 en 1H con vela roja — close crosses below SMA20 with bearish candle</li>
     *   <li>Cierre en tercio inferior y volumen suficiente — close in bottom 35% of range, volume &ge; 90% avg</li>
     *   <li>Confirmación de tendencia bajista en 15m — price below falling SMA20 on 15m</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [P6] <ticker> @ <time> — Paso X/4 "<description>" → <value> ✅|❌ STOP}
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

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        OpenPriceIndicator open1h   = new OpenPriceIndicator(series1h);
        HighPriceIndicator high1h   = new HighPriceIndicator(series1h);
        LowPriceIndicator low1h     = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h       = new SMAIndicator(close1h, 20);

        // Paso 1: prior uptrend — 3 bars above SMA20
        boolean wasClearUptrend = true;
        int barsBelowSma = 0;
        for (int i = 1; i <= 3; i++) {
            if (close1h.getValue(idx1h - i).doubleValue() <= sma20_1h.getValue(idx1h - i).doubleValue()) {
                wasClearUptrend = false;
                barsBelowSma++;
            }
        }
        final boolean capturedUptrend   = wasClearUptrend;
        final int capturedBarsBelowSma  = barsBelowSma;

        // Paso 2 & 3: breakdown values
        final double currentClose1h = close1h.getValue(idx1h).doubleValue();
        final double currentOpen1h  = open1h.getValue(idx1h).doubleValue();
        final double currentHigh1h  = high1h.getValue(idx1h).doubleValue();
        final double currentLow1h   = low1h.getValue(idx1h).doubleValue();
        final double currentSma1h   = sma20_1h.getValue(idx1h).doubleValue();

        final boolean crossedBelowSma  = currentClose1h < currentSma1h;
        final boolean isBearishCandle  = currentClose1h < currentOpen1h;
        final double candleRange       = currentHigh1h - currentLow1h;
        final boolean closedNearLow    = candleRange > 0 && (currentClose1h - currentLow1h) <= (candleRange * 0.35);

        VolumeIndicator vol1h   = new VolumeIndicator(series1h);
        SMAIndicator avgVol1h   = new SMAIndicator(vol1h, 10);
        final double currentVol = vol1h.getValue(idx1h).doubleValue();
        final double avgVol     = avgVol1h.getValue(idx1h).doubleValue();
        final boolean hasVolume = currentVol >= (avgVol * 0.90);

        // Paso 4: 15m downtrend confirmation
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m       = new SMAIndicator(close15m, 20);
        final double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        final double currentSma15m   = sma20_15m.getValue(idx15m).doubleValue();
        final double prevSma15m      = sma20_15m.getValue(idx15m - 1).doubleValue();
        final boolean isDowntrend15m = (currentPrice15m < currentSma15m) && (currentSma15m < prevSma15m);

        // ---- Four-step condition array (derived from code logic — sin referencia libro) ----

        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test() { return capturedUptrend; }
                @Override public String label() { return "Tendencia alcista previa en 1H (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("bars-below-sma20=%d/3 (need 0); wasAboveSma=%b",
                            capturedBarsBelowSma, capturedUptrend);
                }
            },
            new Condition() {
                @Override public boolean test() { return crossedBelowSma && isBearishCandle; }
                @Override public String label() { return "Ruptura bajista de SMA20 en 1H con vela roja (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("close %.4f < SMA20 %.4f=%b; bearish(close<open)=%b",
                            currentClose1h, currentSma1h, crossedBelowSma, isBearishCandle);
                }
            },
            new Condition() {
                @Override public boolean test() { return closedNearLow && hasVolume; }
                @Override public String label() { return "Cierre en tercio inferior y volumen suficiente (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("closedNearLow=%b (%.1f%% from bottom); vol %.0f>=90%%avg %.0f=%b",
                            closedNearLow,
                            candleRange > 0 ? ((currentClose1h - currentLow1h) / candleRange * 100) : 0,
                            currentVol, avgVol * 0.90, hasVolume);
                }
            },
            new Condition() {
                @Override public boolean test() { return isDowntrend15m; }
                @Override public String label() { return "Confirmación de tendencia bajista en 15m (sin referencia libro)"; }
                @Override public String value() {
                    return String.format("15m price %.4f<SMA %.4f && SMA<prevSMA %.4f=%b",
                            currentPrice15m, currentSma15m, prevSma15m, isDowntrend15m);
                }
            }
        );

        if (!ConditionEvaluator.evaluate("[P6]", ticker, currentTime, conditions, log)) {
            return false;
        }

        lastTriggerMap.put(ticker, currentTime);
        return true;
    }
}
