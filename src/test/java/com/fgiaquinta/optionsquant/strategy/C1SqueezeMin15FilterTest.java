package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for the MIN_15 body filter and BB width check introduced in the
 * C1/P1 Squeeze fix.
 *
 * Changes under test:
 *   1. Body filter moved from HOUR_1 to MIN_15.
 *   2. BB width on MIN_15 must exceed threshold * avg-width of last 20 bars.
 *
 * Helper contract:
 *   - HOUR_1 candle is always a valid bullish/bearish breakout that clears the
 *     channel ceiling/floor with the 0.3% buffer.  It has no meaningful body
 *     constraint by itself — the body constraint now lives on MIN_15.
 *   - 20 historical MIN_15 bars are built with a fixed close so BB width is
 *     deterministic.  The last (current) 15m bar controls body + BB width.
 */
class C1SqueezeMin15FilterTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    /**
     * Builds a synthetic 15m series where historical bars oscillate between
     * {@code center - halfRange} and {@code center + halfRange} (alternating).
     * This produces a deterministic, non-zero BB avg-width so the "lateral" test
     * case can set a current close within the same oscillation band and stay below
     * the 1.1× expansion threshold.
     *
     * BB width is proportional to stddev of close prices over the 20-bar window.
     * With alternating closes at ±halfRange, stddev ≈ halfRange (constant for all bars).
     * So current BB width ≈ historical avg BB width → ratio ≈ 1.0 < 1.1 threshold.
     *
     * For the expanded case: current close = center + halfRange * 8, which spikes
     * the stddev drastically so current BB width >> avg * 1.1.
     *
     * @param histCenter    midpoint of the historical oscillation band
     * @param halfRange     half the oscillation amplitude (e.g. 0.5 → alternates 99.5 / 100.5)
     * @param currentOpen   open of the current (trigger) 15m bar
     * @param expandedBB    when true: current close = histCenter + halfRange*8 (wide BB);
     *                      when false: current close = histCenter + halfRange (stays in band)
     */
    private List<Candle> build15mCandles(
            ZonedDateTime currentTime,
            double histCenter,
            double halfRange,
            double currentOpen,
            boolean bullishCurrent,
            boolean expandedBB
    ) {
        List<Candle> candles = new ArrayList<>();
        ZonedDateTime base15m = currentTime.minusMinutes(15L * 21);

        // 20 historical bars oscillating ±halfRange — produces stable, non-trivial stddev
        for (int i = 0; i < 20; i++) {
            double close = (i % 2 == 0) ? histCenter - halfRange : histCenter + halfRange;
            candles.add(candle(
                    base15m.plusMinutes(15L * i),
                    close, close + halfRange * 0.1, close - halfRange * 0.1, close, 500000L
            ));
        }

        // Current bar: expanded = spike the close far outside the band; lateral = stay within
        double currentClose;
        if (expandedBB) {
            // Force a large move so BB width >> avg * 1.1
            currentClose = bullishCurrent ? histCenter + halfRange * 8 : histCenter - halfRange * 8;
        } else {
            // Stay inside the normal oscillation band — BB width ≈ avg (ratio < 1.1)
            currentClose = bullishCurrent ? histCenter + halfRange : histCenter - halfRange;
        }

        candles.add(candle(
                currentTime,
                currentOpen,
                Math.max(currentOpen, currentClose) + halfRange * 0.1,
                Math.min(currentOpen, currentClose) - halfRange * 0.1,
                currentClose,
                3000000L
        ));
        return candles;
    }

    /**
     * Builds StrategyData where:
     * - HOUR_1 always triggers a valid bullish breakout.
     * - The last MIN_15 bar is controlled via expandedBB and bullishCurrent.
     */
    private StrategyData buildC1Data(
            ZonedDateTime currentTime,
            boolean bullishCurrent,
            boolean expandedBB
    ) {
        double ceiling = 100.0;
        double h1BreakoutOpen = ceiling * 0.995;
        double h1BreakoutClose = ceiling * 1.004; // +0.4% above ceiling → clears 0.3% buffer

        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        for (int i = 0; i < totalBars - 1; i++) {
            double base = ceiling - 1.0 + (i % 3) * 0.2 - 0.2;
            hourlyCandles.add(candle(hourBase.plusHours(i), base, ceiling, base - 0.5, base, 2000000L));
        }
        hourlyCandles.add(candle(
                currentTime,
                h1BreakoutOpen,
                h1BreakoutClose + 0.1,
                h1BreakoutOpen - 0.1,
                h1BreakoutClose,
                2000000L
        ));

        // 15m data: oscillate around 100.0 ±0.5 — deterministic stddev baseline
        List<Candle> candles15m = build15mCandles(currentTime, 100.0, 0.5, 100.0, bullishCurrent, expandedBB);

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourlyCandles);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    /**
     * Builds StrategyData where:
     * - HOUR_1 always triggers a valid bearish breakout.
     * - The last MIN_15 bar is controlled via expandedBB and bullishCurrent.
     */
    private StrategyData buildP1Data(
            ZonedDateTime currentTime,
            boolean bullishCurrent,
            boolean expandedBB
    ) {
        double floor = 100.0;
        double h1BreakoutOpen = floor * 1.005;
        double h1BreakoutClose = floor * 0.996; // -0.4% below floor → clears 0.3% buffer

        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        for (int i = 0; i < totalBars - 1; i++) {
            double base = floor + 1.0 + (i % 3) * 0.2 - 0.2;
            hourlyCandles.add(candle(hourBase.plusHours(i), base, base + 0.5, floor, base, 2000000L));
        }
        hourlyCandles.add(candle(
                currentTime,
                h1BreakoutOpen,
                h1BreakoutOpen + 0.1,
                h1BreakoutClose - 0.1,
                h1BreakoutClose,
                2000000L
        ));

        // 15m data: oscillate around 100.0 ±0.5 — deterministic stddev baseline
        List<Candle> candles15m = build15mCandles(currentTime, 100.0, 0.5, 100.0, bullishCurrent, expandedBB);

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourlyCandles);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    // =========================================================================
    // C1 — MIN_15 body filter and BB width tests
    // =========================================================================

    @Nested
    @DisplayName("C1SqueezeCallStrategy — MIN_15 body and BB width filters")
    class C1Min15FilterTests {

        /**
         * HOUR_1 is bullish and clears the breakout buffer.
         * MIN_15 last bar: bearish (close < open) → body filter on MIN_15 must reject.
         *
         * Pre-fix: body filter ran on HOUR_1, so this would have PASSED (H1 is bullish).
         * Post-fix: body filter runs on MIN_15, so this must FAIL (15m is bearish).
         */
        @Test
        @DisplayName("c1_hour1BullishButMin15Bearish_doesNotTrigger")
        void c1_hour1BullishButMin15Bearish_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // bullishCurrent=false → bearish 15m body; expandedBB=false → no expansion needed
            StrategyData data = buildC1Data(testTime, false, false);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003, 0.002, 1.1);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        /**
         * MIN_15 bar is bullish (histCenter+halfRange close > histCenter open)
         * AND BB is expanded (current close at histCenter + halfRange*8 → width >> avg*1.1).
         * All HOUR_1 conditions are satisfied. Must trigger.
         */
        @Test
        @DisplayName("c1_min15BullishWithExpandedBB_triggers")
        void c1_min15BullishWithExpandedBB_triggers() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // bullishCurrent=true, expandedBB=true → close spikes to center + halfRange*8
            StrategyData data = buildC1Data(testTime, true, true);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003, 0.002, 1.1);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isTrue();
        }

        /**
         * MIN_15 bar is bullish (close slightly above open, within the normal oscillation band)
         * BUT BB is lateral — current width ≈ avg width, ratio < 1.1.
         * Must NOT trigger.
         */
        @Test
        @DisplayName("c1_min15BullishButBBLateral_doesNotTrigger")
        void c1_min15BullishButBBLateral_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // bullishCurrent=true, expandedBB=false → close stays within ±halfRange band
            StrategyData data = buildC1Data(testTime, true, false);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003, 0.002, 1.1);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }
    }

    // =========================================================================
    // P1 — MIN_15 body filter and BB width tests (mirrors of C1)
    // =========================================================================

    @Nested
    @DisplayName("P1SqueezePutStrategy — MIN_15 body and BB width filters")
    class P1Min15FilterTests {

        /**
         * HOUR_1 is bearish and clears the breakout buffer.
         * MIN_15 last bar: bullish (close > open) → body filter on MIN_15 must reject.
         *
         * Pre-fix: body filter ran on HOUR_1, so this would have PASSED (H1 is bearish).
         * Post-fix: body filter runs on MIN_15, so this must FAIL (15m is bullish).
         */
        @Test
        @DisplayName("p1_hour1BearishButMin15Bullish_doesNotTrigger")
        void p1_hour1BearishButMin15Bullish_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // bullishCurrent=true → bullish 15m body; P1 requires bearish → must reject
            StrategyData data = buildP1Data(testTime, true, false);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003, 0.002, 1.1);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        /**
         * MIN_15 bar is bearish (close at center - halfRange*8 → wide BB)
         * AND BB is expanded. All HOUR_1 conditions are satisfied. Must trigger.
         */
        @Test
        @DisplayName("p1_min15BearishWithExpandedBB_triggers")
        void p1_min15BearishWithExpandedBB_triggers() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // bullishCurrent=false, expandedBB=true → bearish close spikes down, BB expands
            StrategyData data = buildP1Data(testTime, false, true);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003, 0.002, 1.1);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isTrue();
        }

        /**
         * MIN_15 bar is bearish (close slightly below open, within normal band)
         * BUT BB is lateral — current width ≈ avg width, ratio < 1.1. Must NOT trigger.
         */
        @Test
        @DisplayName("p1_min15BearishButBBLateral_doesNotTrigger")
        void p1_min15BearishButBBLateral_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // bullishCurrent=false, expandedBB=false → close stays within ±halfRange band
            StrategyData data = buildP1Data(testTime, false, false);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003, 0.002, 1.1);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }
    }
}
