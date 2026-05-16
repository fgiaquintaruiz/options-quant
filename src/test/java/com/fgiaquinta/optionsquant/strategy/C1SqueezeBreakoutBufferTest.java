package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for the breakout buffer feature in C1 and P1 strategies.
 *
 * Buffer semantics:
 *   C1 (bullish): close must be > ceiling * (1 + bufferPct) to fire.
 *   P1 (bearish): close must be < floor  * (1 - bufferPct) to fire.
 *
 * Default buffer: 0.3% (0.003).
 */
class C1SqueezeBreakoutBufferTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    /**
     * Builds StrategyData for C1 (bullish) tests.
     *
     * @param currentTime   the signal evaluation time
     * @param ceilingPrice  price used as the 70-bar high ceiling (all historical highs set to this)
     * @param breakoutClose the close of the final (breakout) hourly bar
     * @param breakoutOpen  the open  of the final (breakout) hourly bar — must be < breakoutClose for green candle
     */
    private StrategyData buildC1Data(
            ZonedDateTime currentTime,
            double ceilingPrice,
            double breakoutClose,
            double breakoutOpen
    ) {
        // 280 hourly bars: 279 historical (laterally compressed) + 1 breakout bar
        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        for (int i = 0; i < totalBars - 1; i++) {
            // Tight lateral channel: all closes near ceilingPrice - 1.0, all highs = ceilingPrice
            double base = ceilingPrice - 1.0 + (i % 3) * 0.2 - 0.2;
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    base,           // open
                    ceilingPrice,   // high = ceiling (establishes the 70-bar max)
                    base - 0.5,     // low
                    base,           // close
                    2000000L
            ));
        }

        // Final (breakout) bar
        hourlyCandles.add(candle(
                currentTime,
                breakoutOpen,
                breakoutClose + 0.1, // high slightly above close
                breakoutOpen - 0.1,
                breakoutClose,
                2000000L
        ));

        // 15m candles: 20 historical + 1 current riding upper Bollinger Band
        // Historical 15m closes all = 100 → upper band ≈ 100; current close = breakoutClose (high)
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime marketOpen = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                .minusMinutes(15L * 20);
        for (int i = 0; i < 20; i++) {
            double close = 100.0;
            candles15m.add(candle(
                    marketOpen.plusMinutes(15L * i),
                    close, close + 0.5, close - 0.5, close, 500000L
            ));
        }
        // Current 15m riding upper band: use breakoutClose so it's well above 100
        candles15m.add(candle(
                currentTime,
                breakoutOpen,
                breakoutClose + 0.1,
                breakoutOpen - 0.1,
                breakoutClose,
                3000000L
        ));

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourlyCandles);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    /**
     * Builds StrategyData for P1 (bearish) tests.
     *
     * @param currentTime   the signal evaluation time
     * @param floorPrice    price used as the 70-bar low floor (all historical lows set to this)
     * @param breakoutClose the close of the final (breakout) hourly bar
     * @param breakoutOpen  the open  of the final (breakout) hourly bar — must be > breakoutClose for red candle
     */
    private StrategyData buildP1Data(
            ZonedDateTime currentTime,
            double floorPrice,
            double breakoutClose,
            double breakoutOpen
    ) {
        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        for (int i = 0; i < totalBars - 1; i++) {
            double base = floorPrice + 1.0 + (i % 3) * 0.2 - 0.2;
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    base,
                    base + 0.5,
                    floorPrice,   // low = floor (establishes the 70-bar min)
                    base,
                    2000000L
            ));
        }

        // Final (breakout) bar — red candle: open > close
        hourlyCandles.add(candle(
                currentTime,
                breakoutOpen,
                breakoutOpen + 0.1,
                breakoutClose - 0.1,
                breakoutClose,
                2000000L
        ));

        // 15m candles: 20 historical (close=100) + 1 current riding lower BB
        // Lower band ≈ 100; use breakoutClose (well below 100) for the current bar
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime marketOpen = currentTime.toLocalDate().atTime(9, 30).atZone(NY)
                .minusMinutes(15L * 20);
        for (int i = 0; i < 20; i++) {
            double close = 100.0;
            candles15m.add(candle(
                    marketOpen.plusMinutes(15L * i),
                    close, close + 0.5, close - 0.5, close, 500000L
            ));
        }
        candles15m.add(candle(
                currentTime,
                breakoutOpen,
                breakoutOpen + 0.1,
                breakoutClose - 0.1,
                breakoutClose,
                3000000L
        ));

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourlyCandles);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    // =========================================================================
    // C1 — Bullish buffer tests
    // =========================================================================

    @Nested
    @DisplayName("C1SqueezeCallStrategy — breakout buffer")
    class C1BufferTests {

        @Test
        @DisplayName("closedAtExactCeiling_shouldNotTrigger — close == ceiling, no buffer clearance")
        void closedAtExactCeiling_shouldNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
            double ceiling = 100.0;

            // close == ceiling: without buffer this would fire; with 0.3% buffer it must NOT
            StrategyData data = buildC1Data(testTime, ceiling, ceiling, ceiling - 1.0);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("closedAtBufferEdge_shouldNotTrigger — close == ceiling * 1.003, exactly at 0.3%")
        void closedAtBufferEdge_shouldNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
            double ceiling = 100.0;
            double closeAtEdge = ceiling * 1.003; // exactly at buffer — not strictly above

            StrategyData data = buildC1Data(testTime, ceiling, closeAtEdge, ceiling - 1.0);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("closedAboveBuffer_shouldTrigger — close == ceiling * 1.004, strictly above 0.3% buffer")
        void closedAboveBuffer_shouldTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
            double ceiling = 100.0;
            double closeAboveBuffer = ceiling * 1.004; // 0.4% above ceiling — clears buffer

            StrategyData data = buildC1Data(testTime, ceiling, closeAboveBuffer, ceiling - 1.0);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isTrue();
        }
    }

    // =========================================================================
    // P1 — Bearish buffer tests
    // =========================================================================

    @Nested
    @DisplayName("P1SqueezePutStrategy — breakout buffer")
    class P1BufferTests {

        @Test
        @DisplayName("p1_closedAtExactFloor_shouldNotTrigger — close == floor, no buffer clearance")
        void p1_closedAtExactFloor_shouldNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
            double floor = 100.0;

            // close == floor: without buffer this would fire; with buffer it must NOT
            StrategyData data = buildP1Data(testTime, floor, floor, floor + 1.0);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("p1_closedBelowBuffer_shouldTrigger — close == floor * 0.996, strictly below buffer")
        void p1_closedBelowBuffer_shouldTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
            double floor = 100.0;
            double closeBelowBuffer = floor * 0.996; // 0.4% below floor — clears buffer

            StrategyData data = buildP1Data(testTime, floor, closeBelowBuffer, floor + 1.0);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isTrue();
        }
    }
}
