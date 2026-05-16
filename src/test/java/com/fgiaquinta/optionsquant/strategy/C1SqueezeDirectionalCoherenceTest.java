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
 * TDD tests for directional candle coherence filter in C1 and P1 strategies.
 *
 * C1 (bullish): candle body must be >= +minBodyPct (close - open) / open.
 * P1 (bearish): candle body must be <= -minBodyPct (close - open) / open.
 *
 * Default minBodyPct: 0.2% (0.002).
 * All tests use a ceiling/floor that is already cleared with a 0.3% buffer,
 * so the only variable is the candle body size and direction.
 */
class C1SqueezeDirectionalCoherenceTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    /**
     * Builds StrategyData for C1 (bullish) tests.
     *
     * The historical 70-bar ceiling is set to (breakoutOpen - 0.5), so
     * breakoutClose only needs to be above ceiling * 1.003 to satisfy the
     * breakout-buffer check. The candle body (open vs close) is then the only
     * remaining variable under test.
     */
    private StrategyData buildC1Data(
            ZonedDateTime currentTime,
            double breakoutOpen,
            double breakoutClose
    ) {
        double ceilingPrice = breakoutOpen - 0.5; // breakout bar will always clear the buffer
        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        for (int i = 0; i < totalBars - 1; i++) {
            double base = ceilingPrice - 1.0 + (i % 3) * 0.2 - 0.2;
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    base,
                    ceilingPrice,
                    base - 0.5,
                    base,
                    2000000L
            ));
        }

        hourlyCandles.add(candle(
                currentTime,
                breakoutOpen,
                breakoutClose + 0.1,
                breakoutOpen - 0.1,
                breakoutClose,
                2000000L
        ));

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
        // Current 15m riding upper band: use breakoutClose (well above 100)
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
     * The historical 70-bar floor is set to (breakoutOpen + 0.5), so
     * breakoutClose only needs to be below floor * 0.997 to satisfy the
     * breakout-buffer check. The candle body is then the only remaining variable.
     */
    private StrategyData buildP1Data(
            ZonedDateTime currentTime,
            double breakoutOpen,
            double breakoutClose
    ) {
        double floorPrice = breakoutOpen + 0.5; // breakout bar will always clear the buffer
        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        for (int i = 0; i < totalBars - 1; i++) {
            double base = floorPrice + 1.0 + (i % 3) * 0.2 - 0.2;
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    base,
                    base + 0.5,
                    floorPrice,
                    base,
                    2000000L
            ));
        }

        hourlyCandles.add(candle(
                currentTime,
                breakoutOpen,
                breakoutOpen + 0.1,
                breakoutClose - 0.1,
                breakoutClose,
                2000000L
        ));

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
    // C1 — Directional coherence tests
    // =========================================================================

    @Nested
    @DisplayName("C1SqueezeCallStrategy — directional candle coherence")
    class C1DirectionalCoherenceTests {

        @Test
        @DisplayName("c1_bearishCandle_doesNotTrigger — close < open: body is negative, filter must reject")
        void c1_bearishCandle_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // open=101, close=100.5 → body = (100.5-101)/101 = -0.5% (bearish)
            double open = 101.0;
            double close = 100.5;

            StrategyData data = buildC1Data(testTime, open, close);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003, 0.002);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("c1_dojiCandle_doesNotTrigger — close == open: body = 0%, below minBodyPct threshold")
        void c1_dojiCandle_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // open == close → body = 0% < 0.2%
            double open = 101.0;
            double close = 101.0;

            StrategyData data = buildC1Data(testTime, open, close);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003, 0.002);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("c1_tinyBullishBelowThreshold_doesNotTrigger — body = +0.05% < 0.2% threshold")
        void c1_tinyBullishBelowThreshold_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // open=101, close=101.0505 → body ≈ +0.05% (below 0.2% threshold)
            double open = 101.0;
            double close = open * 1.0005; // +0.05%

            StrategyData data = buildC1Data(testTime, open, close);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003, 0.002);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("c1_clearBullishAboveThreshold_triggers — body = +0.3% > 0.2% + all conditions met")
        void c1_clearBullishAboveThreshold_triggers() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // open=101, close=101.303 → body ≈ +0.3% (above 0.2% threshold)
            double open = 101.0;
            double close = open * 1.003; // +0.3%

            StrategyData data = buildC1Data(testTime, open, close);
            C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003, 0.002);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isTrue();
        }
    }

    // =========================================================================
    // P1 — Directional coherence tests (mirrors of C1)
    // =========================================================================

    @Nested
    @DisplayName("P1SqueezePutStrategy — directional candle coherence")
    class P1DirectionalCoherenceTests {

        @Test
        @DisplayName("p1_bullishCandle_doesNotTrigger — close > open: body is positive, filter must reject")
        void p1_bullishCandle_doesNotTrigger() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // P1 data: open > close (bearish) required for breakout; here we force close > open (bullish)
            // open=99, close=99.5 → body = +0.5% (bullish — P1 must reject)
            // But P1 breakout check also requires close < floor*(1-buffer), so we need data that
            // passes the breakout test for a red-candle scenario but we're testing body direction.
            // Use the P1 builder but pass open < close so the candle is bullish.
            double open = 99.0;
            double close = 99.5; // close > open — bullish candle

            StrategyData data = buildP1Data(testTime, open, close);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003, 0.002);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isFalse();
        }

        @Test
        @DisplayName("p1_clearBearishAboveThreshold_triggers — body = -0.3% + all conditions met")
        void p1_clearBearishAboveThreshold_triggers() {
            ZonedDateTime testTime = ZonedDateTime.of(2026, 5, 13, 14, 0, 0, 0, NY);
            // open=99, close=98.703 → body ≈ -0.3% (below -0.2% threshold)
            double open = 99.0;
            double close = open * 0.997; // -0.3%

            StrategyData data = buildP1Data(testTime, open, close);
            P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003, 0.002);

            assertThat(strategy.isTriggered("TEST", data, testTime)).isTrue();
        }
    }
}
