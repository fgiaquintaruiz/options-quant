package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import lombok.extern.slf4j.Slf4j;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class C3BounceCallStrategy implements TradingStrategy, TimeframeRequirements {

    // ANTI-MACHINE-GUN: 2-hour cooldown
    private final Map<String, ZonedDateTime> lastTriggerMap = new HashMap<>();

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1);
    }

    /**
     * Evaluates the C3 Bounce Call strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through four sequential book steps (short-circuit on first failure):
     * <ol>
     *   <li>Tendencia clara en Bollinger temporalidad hora — 1D uptrend AND price recently broke below 1H lower BB</li>
     *   <li>Precio acercándose a SMA20 diaria como punto de rebote — 1H low touched SMA20 support zone (within 0.2%)</li>
     *   <li>Precio respeta el punto, en 15m comienza a rebotar — 15m price above SMA20 (reversal confirmed)</li>
     *   <li>En hora, vela de confirmación → entrada — 1H close above SMA20 (bullish rejection candle)</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [C3] <ticker> @ <time> — Paso X/4 "<libro description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all four steps are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {

        // =========================================================================
        // RULE 0.1: OPENING FILTER (Block 9 AM NY)
        // =========================================================================
        ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));
        if (nyTime.getHour() == 9) {
            return false;
        }

        // =========================================================================
        // RULE 0.2: 2-HOUR COOLDOWN (Re-entry blocker)
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
        final boolean isUptrend = dailyClose1 > dailyClose2;

        BollingerBandsUtil bb1h = new BollingerBandsUtil(series1h, 20);
        final boolean wasInBearishBBContext = bb1h.brokeBelowLowerBand(idx1h - 1, 5);

        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        LowPriceIndicator low1h = new LowPriceIndicator(series1h);
        SMAIndicator sma20_1h = new SMAIndicator(close1h, 20);

        final double currentLow1h = low1h.getValue(idx1h).doubleValue();
        final double currentClose1h = close1h.getValue(idx1h).doubleValue();
        final double sma20Val1h = sma20_1h.getValue(idx1h).doubleValue();

        final boolean touchedSupport = currentLow1h <= (sma20Val1h * 1.002);
        final boolean rejectedSupport = currentClose1h > sma20Val1h;

        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        SMAIndicator sma20_15m = new SMAIndicator(close15m, 20);

        final double currentPrice15m = close15m.getValue(idx15m).doubleValue();
        final double sma20Val15m = sma20_15m.getValue(idx15m).doubleValue();
        final boolean confirmedUptrend15m = currentPrice15m > sma20Val15m;

        // ---- Four-step condition array (mapped to the course author libro 3/4) ----

        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test()  { return isUptrend && wasInBearishBBContext; }
                @Override public String label()  { return "Tendencia clara en Bollinger temporalidad hora"; }
                @Override public String value()  {
                    return String.format("1D close %.4f>%.4f=%b; 1H brokeBelowLowerBand(5)=%b",
                            dailyClose1, dailyClose2, isUptrend, wasInBearishBBContext);
                }
            },
            new Condition() {
                @Override public boolean test()  { return touchedSupport; }
                @Override public String label()  { return "Precio acercándose a SMA20 diaria como punto de rebote"; }
                @Override public String value()  {
                    return String.format("1H low %.4f <= SMA20*1.002 %.4f=%b",
                            currentLow1h, sma20Val1h * 1.002, touchedSupport);
                }
            },
            new Condition() {
                @Override public boolean test()  { return confirmedUptrend15m; }
                @Override public String label()  { return "Precio respeta el punto, en 15m comienza a rebotar"; }
                @Override public String value()  {
                    return String.format("15m close %.4f > SMA20 %.4f=%b",
                            currentPrice15m, sma20Val15m, confirmedUptrend15m);
                }
            },
            new Condition() {
                @Override public boolean test()  { return rejectedSupport; }
                @Override public String label()  { return "En hora, vela de confirmación → entrada"; }
                @Override public String value()  {
                    return String.format("1H close %.4f > SMA20 %.4f=%b",
                            currentClose1h, sma20Val1h, rejectedSupport);
                }
            }
        );

        final int total = conditions.size();
        for (int step = 0; step < total; step++) {
            final Condition c = conditions.get(step);
            if (log.isDebugEnabled()) {
                final String stepPrefix = String.format("[C3] %s @ %s — Paso %d/%d \"%s\" → %s",
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

        lastTriggerMap.put(ticker, currentTime);
        return true;
    }
}
