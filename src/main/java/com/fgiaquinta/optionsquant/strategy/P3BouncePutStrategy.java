package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import com.fgiaquinta.optionsquant.strategy.utils.ConditionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class P3BouncePutStrategy implements TradingStrategy, TimeframeRequirements {

    // ANTI-MACHINE-GUN: 2-hour cooldown
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Evaluates the P3 Bounce Put strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through four sequential book steps (short-circuit on first failure):
     * <ol>
     *   <li>Tendencia bajista clara en Bollinger temporalidad hora — 1D downtrend AND price recently broke above 1H upper BB</li>
     *   <li>Precio acercándose a SMA20 diaria como punto de rebote bajista — 1H high touched SMA20 resistance zone (within 0.2%)</li>
     *   <li>Precio respeta el punto, en 15m comienza a rebotar a la baja — 15m price below SMA20 (bearish reversal confirmed)</li>
     *   <li>En hora, vela de confirmación bajista → entrada — 1H close below SMA20 (bearish rejection candle)</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [P3] <ticker> @ <time> — Paso X/4 "<libro description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all four steps are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        // =========================================================================
        // RULE 0.1: OPENING FILTER (Don't operate P3 in the first 30 min)
        // =========================================================================
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));
        if (nyTime.getHour() < 10) {
            return false;
        }

        // =========================================================================
        // RULE 0.3: 2-HOUR COOLDOWN (Re-entry blocker)
        // =========================================================================
        ZonedDateTime lastTrigger = lastTriggerMap.get(ticker);
        if (lastTrigger != null && Duration.between(lastTrigger, currentTime).toHours() < 2) {
            return false;
        }

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

        ClosePriceIndicator close1D = new ClosePriceIndicator(series1D);
        final double dailyClose1 = close1D.getValue(idx1D - 1).doubleValue();
        final double dailyClose2 = close1D.getValue(idx1D - 2).doubleValue();
        final boolean isDowntrend = dailyClose1 < dailyClose2;

        BollingerBandsUtil bb1h = new BollingerBandsUtil(series1h, 20);
        final boolean wasInBullishBBContext = bb1h.brokeAboveUpperBand(idx1h - 1, 10);

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        HighPriceIndicator high1h = new HighPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        final double currentHigh1h = high1h.getValue(idx1h).doubleValue();
        final double currentClose1h = close1h.getValue(idx1h).doubleValue();
        final double sma20Val1h = sma20_1h.getValue(idx1h).doubleValue();

        final boolean touchedResistance = currentHigh1h >= (sma20Val1h * 0.998);
        final boolean rejectedResistance = currentClose1h < sma20Val1h;

        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        final double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        final double sma20Val15m = sma20_15m.getValue(idx15m).doubleValue();
        final boolean confirmedDowntrend15m = currentPrice15m < sma20Val15m;

        // ---- Four-step condition array (mapped to the course author libro 3/4 — bearish) ----

        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test()  { return isDowntrend && wasInBullishBBContext; }
                @Override public String label()  { return "Tendencia bajista clara en Bollinger temporalidad hora"; }
                @Override public String value()  {
                    return String.format("1D close %.4f<%.4f=%b; 1H brokeAboveUpperBand(10)=%b",
                            dailyClose1, dailyClose2, isDowntrend, wasInBullishBBContext);
                }
            },
            new Condition() {
                @Override public boolean test()  { return touchedResistance; }
                @Override public String label()  { return "Precio acercándose a SMA20 diaria como punto de rebote bajista"; }
                @Override public String value()  {
                    return String.format("1H high %.4f >= SMA20*0.998 %.4f=%b",
                            currentHigh1h, sma20Val1h * 0.998, touchedResistance);
                }
            },
            new Condition() {
                @Override public boolean test()  { return confirmedDowntrend15m; }
                @Override public String label()  { return "Precio respeta el punto, en 15m comienza a rebotar a la baja"; }
                @Override public String value()  {
                    return String.format("15m close %.4f < SMA20 %.4f=%b",
                            currentPrice15m, sma20Val15m, confirmedDowntrend15m);
                }
            },
            new Condition() {
                @Override public boolean test()  { return rejectedResistance; }
                @Override public String label()  { return "En hora, vela de confirmación bajista → entrada"; }
                @Override public String value()  {
                    return String.format("1H close %.4f < SMA20 %.4f=%b",
                            currentClose1h, sma20Val1h, rejectedResistance);
                }
            }
        );

        if (!ConditionEvaluator.evaluate("[P3]", ticker, nyTime, conditions, log)) {
            return false;
        }

        lastTriggerMap.put(ticker, currentTime);
        return true;
    }
}
