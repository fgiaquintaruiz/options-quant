package com.fgiaquinta.optionsquant.strategy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — FAILING FIRST.
 *
 * Verifies that P2TrendPutStrategy emits per-condition DEBUG log lines in the format:
 *   [P2] <ticker> @ <time> — Paso X/4 "<step description>" → <value> ✅  (or ❌ STOP)
 *
 * P2 has 8 technical checks mapped to 4 steps:
 *   Paso 1/4 — "Tendencia bajista establecida (1D + 1H bajo SMA20)"
 *   Paso 2/4 — "Pullback rechazado en SMA20 (toca y cierra por debajo)"
 *   Paso 3/4 — "Vela bajista con presión vendedora (cuerpo + mecha + volumen)"
 *   Paso 4/4 — "Tendencia bajista total en 15m (SMA + Bollinger)"
 */
class P2ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger p2Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        p2Logger = (Logger) LoggerFactory.getLogger(P2TrendPutStrategy.class);
        logAppender.start();
        p2Logger.addAppender(logAppender);
        p2Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (p2Logger != null && logAppender != null) {
            p2Logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // =========================================================================
    // Shared data builders
    // =========================================================================

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    /**
     * Builds StrategyData that causes P2 to FAIL at Paso 1/4 (no daily downtrend).
     *
     * 1D: uptrend — close[i-1] > close[i-2] so isDailyDowntrend = false
     * 1H: irrelevant (step 1 stops first)
     * 15m: irrelevant
     */
    private StrategyData buildDataThatFailsStep1_DailyTrend(ZonedDateTime currentTime) {
        // 1D: uptrend bars so the last close > previous close
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(25);
        for (int i = 0; i < 24; i++) {
            double price = 100.0 + i * 0.5; // ascending prices
            daily.add(candle(dayBase.plusDays(i), price, price + 1, price - 1, price, 5000000L));
        }
        // Last 1D bar (current): still going up
        daily.add(candle(currentTime, 112.0, 113.0, 111.0, 112.5, 5000000L));

        // 1H: 25 flat bars (enough idx but not relevant since step 1 fails)
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        for (int i = 0; i < 25; i++) {
            hourly.add(candle(hourBase.plusHours(i), 110.0, 110.5, 109.5, 110.0, 1000000L));
        }

        // 15m: 25 flat bars
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(25L * 15);
        for (int i = 0; i < 25; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 110.0, 110.5, 109.5, 110.0, 500000L));
        }

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.DAY_1, daily);
        data.put(TimeFrame.HOUR_1, hourly);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    /**
     * Builds StrategyData that PASSES steps 1–2 but FAILS at Paso 3/4 (candle/volume check).
     *
     * SMA20 construction strategy: use exactly 20 bars at 100.0 so SMA20 = 100.0 exactly.
     * Then 3 bars at 95.0 (below SMA, step 1 passes). Then current bar:
     *   - high = 99.6 >= 100.0 * 0.995 = 99.5 → touchedResistance = true
     *   - close = 99.8 < 100.0 → rejectedResistance = true → step 2 passes
     *   - close (99.8) > open (98.0) → isBearishCandle = FALSE → step 3 fails
     *
     * SMA20 at index 23 (with 20+3+1 bars total, idx=23):
     *   The 20 most recent closes feeding SMA20 are bars[4..23]: bars 4-19 = 100.0 (16 bars),
     *   bars 20-22 = 95.0 (3 bars), bar 23 = 99.8 (1 bar) → avg = (16*100 + 3*95 + 99.8)/20 = 99.74
     *   99.6 >= 99.74 * 0.995 = 99.24 → touchedResistance = TRUE ✓
     *   99.8 < 99.74 → rejectedResistance = FALSE ✗  (close > SMA20)
     *
     * Recalculate: SMA20 at idx=23: last 20 closes = bars[4..23]
     *   bars 4..19: 16 bars at 100.0 = 1600
     *   bars 20..22: 3 bars at 95.0  = 285
     *   bar 23: close = 99.8         = 99.8
     *   sum = 1984.8 / 20 = 99.24
     *   Need close < 99.24 AND high >= 99.24 * 0.995 = 98.74
     *   Use: open=97.0, high=99.5, low=96.5, close=99.0
     *   → high(99.5) >= 98.74 ✓; close(99.0) < 99.24 ✓; close(99.0) > open(97.0) → BULLISH → step 3 fails ✓
     */
    private StrategyData buildDataThatFailsStep3_BearishCandle(ZonedDateTime currentTime) {
        // 1D: downtrend — 26 descending daily bars (idx1D ≥ 20, last close < prev close)
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(26);
        for (int i = 0; i < 26; i++) {
            double price = 150.0 - i * 1.0; // 150, 149, ..., 125 — strictly descending
            daily.add(candle(dayBase.plusDays(i), price + 0.5, price + 1, price - 1, price, 5000000L));
        }

        // 1H: 24 bars total (idx = 23, ≥ 20 ✓)
        // Bars 0–19: close = 100.0  (20 bars, anchor the SMA)
        // Bars 20–22: close = 95.0  (3 bars below SMA20 → step 1 wasBelowSma passes)
        // Bar 23 (current): BULLISH candle that still satisfies step 2 but breaks step 3
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(24);
        for (int i = 0; i < 20; i++) {
            hourly.add(candle(hourBase.plusHours(i), 100.0, 101.0, 99.0, 100.0, 1000000L));
        }
        for (int i = 20; i < 23; i++) {
            hourly.add(candle(hourBase.plusHours(i), 95.5, 96.0, 94.5, 95.0, 1000000L));
        }
        // SMA20 at idx=23: closes from idx[4..23]
        //   bars 4..19: 16 × 100.0 = 1600
        //   bars 20..22: 3 × 95.0  = 285
        //   bar 23: close = 99.0   = 99.0 → sum = 1984 / 20 = 99.2
        // touchedResistance: high(99.5) >= 99.2*0.995 = 98.71 → TRUE ✓
        // rejectedResistance: close(99.0) < 99.2 → TRUE ✓
        // isBearishCandle: close(99.0) > open(97.0) → FALSE → step 3 FAILS ✓
        hourly.add(candle(
                currentTime,
                97.0,    // open
                99.5,    // high — touches SMA20 zone
                96.5,    // low
                99.0,    // close — below SMA20 AND close > open (bullish)
                1200000L // healthy volume (well above 90% avg of 1M)
        ));

        // 15m: 25 flat bars (irrelevant — step 3 stops evaluation)
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(25L * 15);
        for (int i = 0; i < 25; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 100.0, 100.5, 99.5, 100.0, 500000L));
        }

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.DAY_1, daily);
        data.put(TimeFrame.HOUR_1, hourly);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("When daily downtrend condition fails (Paso 1), log contains [P2] prefix with Paso 1/4 and ❌ STOP")
    void whenDailyTrendFails_logContainsPaso1WithStopMarker() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep1_DailyTrend(testTime);
        P2TrendPutStrategy strategy = new P2TrendPutStrategy();

        boolean triggered = strategy.isTriggered("SPY", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit at least one [P2] line
        assertThat(logMessages)
                .as("Expected at least one [P2] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[P2]"));

        // Must contain ❌ STOP
        assertThat(logMessages)
                .as("Expected ❌ STOP for the failed condition")
                .anyMatch(msg -> msg.contains("❌ STOP"));

        // Format: Paso X/4 (4 steps)
        assertThat(logMessages)
                .as("Log line must contain 'Paso X/4' step counter")
                .anyMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        // Description must be quoted
        assertThat(logMessages)
                .as("Condition description must be enclosed in double quotes")
                .anyMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // Arrow separator → before the value
        assertThat(logMessages)
                .as("Log line must use → arrow before the condition value")
                .anyMatch(msg -> msg.contains(" → "));

        // The Paso 1/4 line specifically must end with ❌ STOP
        assertThat(logMessages)
                .as("Paso 1/4 line must contain ❌ STOP marker")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("❌ STOP"));
    }

    @Test
    @DisplayName("When bearish-candle condition fails (Paso 3), log shows steps 1-2 as ✅ and Paso 3/4 as ❌ STOP")
    void whenBearishCandleFails_logShowsStep3AsStop() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep3_BearishCandle(testTime);
        P2TrendPutStrategy strategy = new P2TrendPutStrategy();

        boolean triggered = strategy.isTriggered("AAPL", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit [P2] lines
        assertThat(logMessages)
                .as("Expected [P2] DEBUG log lines")
                .anyMatch(msg -> msg.startsWith("[P2]"));

        // Paso 3/4 must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 3/4 must be ❌ STOP (bearish candle check failed)")
                .anyMatch(msg -> msg.contains("Paso 3/4") && msg.contains("❌ STOP"));

        // No ❌ after Paso 3 (execution stopped at 3)
        assertThat(logMessages)
                .as("No Paso 4/4 lines should appear — execution stopped at step 3")
                .noneMatch(msg -> msg.contains("Paso 4/4"));

        // Format checks on all P2 lines
        assertThat(logMessages)
                .as("All [P2] lines must use 'Paso X/4' format")
                .filteredOn(msg -> msg.startsWith("[P2]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        assertThat(logMessages)
                .as("All [P2] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[P2]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        assertThat(logMessages)
                .as("All [P2] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[P2]"))
                .allMatch(msg -> msg.contains(" → "));
    }
}
