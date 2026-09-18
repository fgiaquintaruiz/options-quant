package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.utils.BollingerBandsUtil;
import com.fgiaquinta.optionsquant.strategy.utils.ConditionEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class P4OpeningPutStrategy implements TradingStrategy, TimeframeRequirements {

    @Override
    public Set<TimeFrame> requiredTimeframes() {
        return Set.of(TimeFrame.MIN_5, TimeFrame.MIN_15);
    }

    /**
     * Evaluates the P4 Opening Put strategy against the provided market data at {@code currentTime}.
     *
     * <p>The evaluation proceeds through three sequential steps (short-circuit on first failure):
     * <ol>
     *   <li>Tendencia totalmente lateral y sin volatilidad en 15m — BB width &lt; 2% on previous 15m bar</li>
     *   <li>Apertura con salto, precio en zona de sobreventa — gap up above upper BB, between +1.5% and +6%</li>
     *   <li>Ejecutar en los primeros 5 minutos de apertura — current bar is red (close &lt; open), time 9:30–9:35 NY</li>
     * </ol>
     *
     * <p>When DEBUG logging is enabled, each step emits a structured log line:
     * {@code [P4] <ticker> @ <time> — Paso X/3 "<step description>" → <value> ✅|❌ STOP}
     *
     * @param ticker      the instrument symbol being evaluated
     * @param data        multi-timeframe market data container
     * @param currentTime the virtual or wall-clock time of evaluation
     * @return {@code true} if all three steps are satisfied; {@code false} on the first failure
     */
    @Override
    public boolean isCall() {
        return false;
    }

    @Override
    public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
        final ZonedDateTime nyTime = currentTime.withZoneSameInstant(ZoneId.of("America/New_York"));

        // Time gate: only 9:30–9:35 AM NY (evaluated before conditions so no logging needed)
        if (nyTime.getHour() != 9 || nyTime.getMinute() < 30 || nyTime.getMinute() > 35) {
            return false;
        }

        final BarSeries series15m = data.getSeries(TimeFrame.MIN_15);
        final BarSeries series5m = data.getSeries(TimeFrame.MIN_5);

        if (series15m == null || series5m == null || series15m.isEmpty() || series5m.isEmpty()) return false;

        final int idx15m = data.getIndexForTime(series15m, currentTime);
        final int idx5m = data.getIndexForTime(series5m, currentTime);
        if (idx15m < 20 || idx5m < 1) return false;

        // ---- Pre-compute all values needed by conditions ----

        BollingerBandsUtil bb = new BollingerBandsUtil(series15m, 20);
        final int prevIdx15m = idx15m - 1;
        final double bbWidthPrev = bb.getWidthPercent(prevIdx15m);
        final boolean isLateral = bb.isLateral(prevIdx15m, 2.0);

        final double openToday = series5m.getBar(idx5m).getOpenPrice().doubleValue();
        final double closeYesterday = series5m.getBar(idx5m - 1).getClosePrice().doubleValue();
        final double upperBB = bb.getUpper(prevIdx15m);
        final boolean isExtremeGapUp = openToday > upperBB;
        final double gapPct = (openToday - closeYesterday) / closeYesterday;
        final boolean gapInRange = gapPct >= 0.015 && gapPct <= 0.06;

        final double currentClose = series5m.getBar(idx5m).getClosePrice().doubleValue();
        final boolean isRedCandle = currentClose < openToday;

        // ---- Three-step condition array (strategy conditions) ----

        final List<Condition> conditions = List.of(
            new Condition() {
                @Override public boolean test() { return isLateral; }
                @Override public String label()  { return "Tendencia totalmente lateral y sin volatilidad en 15m"; }
                @Override public String value()  { return String.format("BB width %.4f%% < 2.0%% lateral=%b", bbWidthPrev, isLateral); }
            },
            new Condition() {
                @Override public boolean test() { return isExtremeGapUp && gapInRange; }
                @Override public String label()  { return "Apertura con salto, precio en zona de sobreventa"; }
                @Override public String value()  {
                    return String.format("open %.4f > upperBB %.4f=%b; gap %.2f%% in [+1.5%% +6%%]=%b",
                            openToday, upperBB, isExtremeGapUp, gapPct * 100, gapInRange);
                }
            },
            new Condition() {
                @Override public boolean test() { return isRedCandle; }
                @Override public String label()  { return "Ejecutar en los primeros 5 minutos de apertura"; }
                @Override public String value()  {
                    return String.format("close %.4f < open %.4f red=%b; time %s",
                            currentClose, openToday, isRedCandle, nyTime.toLocalTime());
                }
            }
        );

        return ConditionEvaluator.evaluate("[P4]", ticker, currentTime, conditions, log);
    }
}
