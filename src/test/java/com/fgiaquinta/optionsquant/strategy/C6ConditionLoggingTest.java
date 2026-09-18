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

import static com.fgiaquinta.optionsquant.strategy.CandleTestFactory.candle;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — FAILING FIRST.
 *
 * Verifies that C6ReversalCallStrategy emits per-condition DEBUG log lines in the format:
 *   [C6] <ticker> @ <time> — Paso X/4 "<description>" → <value> ✅  (or ❌ STOP)
 *
 * C6 has 4 conditions derived from the code logic:
 *   Paso 1/4 — "Tendencia bajista previa en 1H"
 *   Paso 2/4 — "Ruptura alcista de SMA20 en 1H con vela verde"
 *   Paso 3/4 — "Cierre en tercio superior y volumen suficiente"
 *   Paso 4/4 — "Confirmación de tendencia alcista en 15m"
 */
class C6ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger c6Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        c6Logger = (Logger) LoggerFactory.getLogger(C6ReversalCallStrategy.class);
        logAppender.start();
        c6Logger.addAppender(logAppender);
        c6Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (c6Logger != null && logAppender != null) {
            c6Logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // =========================================================================
    // Data builders
    // =========================================================================

    /**
     * Builds StrategyData that FAILS at Paso 1/4 (no prior downtrend).
     *
     * 1H: 25 bars, all closing ABOVE SMA20 → wasClearDowntrend = false.
     * The last 3 prior closes are above SMA20, so Paso 1 fails immediately.
     *
     * SMA20 with all closes at 100.0: SMA20 = 100.0.
     * Prior bars 22, 21, 20: close = 101.0 (above SMA20) → FAIL.
     */
    private StrategyData buildDataThatFailsStep1_NoDowntrend(ZonedDateTime currentTime) {
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        // 20 anchor bars at 100.0
        for (int i = 0; i < 20; i++) {
            hourly.add(candle(hourBase.plusHours(i), 100.0, 100.5, 99.5, 100.0, 1000000L));
        }
        // 4 bars ABOVE SMA20 (uptrend — no downtrend)
        for (int i = 20; i < 24; i++) {
            hourly.add(candle(hourBase.plusHours(i), 101.0, 101.5, 100.5, 101.0, 1000000L));
        }
        // Current bar: bullish breakout above SMA20
        hourly.add(candle(currentTime, 100.5, 102.0, 100.0, 101.8, 2000000L));

        // 1D: 25 flat bars (not relevant — Paso 1 stops first)
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(25);
        for (int i = 0; i < 25; i++) {
            daily.add(candle(dayBase.plusDays(i), 100.0, 101.0, 99.0, 100.0, 5000000L));
        }

        // 15m: 25 flat bars
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

    /**
     * Builds StrategyData that PASSES Paso 1 but FAILS at Paso 2/4 (bearish candle, not bullish).
     *
     * 1H setup:
     *   - Bars 0–19: close = 102.0 (SMA20 anchor ≈ 102.0)
     *   - Bars 20–22: close = 99.0 (below SMA20 → wasClearDowntrend passes for last 3 prior bars)
     *   - Current bar (idx=23): open = 103.0, close = 99.5 → bearish (close < open) → Paso 2 fails.
     *     crossedAboveSma: close(99.5) > SMA20 ≈ 100.875 → FALSE → also fails step 2.
     *
     * SMA20 at idx=23: bars[4..23] = 16×102 + 3×99 + 99.5 = 1632 + 297 + 99.5 = 2028.5 / 20 = 101.425
     * crossedAboveSma: 99.5 > 101.425 → FALSE → Paso 2 fails.
     */
    private StrategyData buildDataThatFailsStep2_NoCrossAboveSma(ZonedDateTime currentTime) {
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        // 20 anchor bars above
        for (int i = 0; i < 20; i++) {
            hourly.add(candle(hourBase.plusHours(i), 102.0, 102.5, 101.5, 102.0, 1000000L));
        }
        // 3 bars below SMA20 (prior downtrend — Paso 1 passes)
        for (int i = 20; i < 23; i++) {
            hourly.add(candle(hourBase.plusHours(i), 99.5, 100.0, 98.5, 99.0, 1000000L));
        }
        // Current bar: close still below SMA20 → crossedAboveSma = false → Paso 2 FAILS
        hourly.add(candle(currentTime, 103.0, 103.5, 99.0, 99.5, 2000000L));

        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(25);
        for (int i = 0; i < 25; i++) {
            daily.add(candle(dayBase.plusDays(i), 100.0, 101.0, 99.0, 100.0, 5000000L));
        }

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
    @DisplayName("When prior downtrend condition fails (Paso 1), log contains [C6] prefix, Paso 1/4, and ❌ STOP")
    void whenDowntrendFails_logContainsPaso1WithStopMarker() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep1_NoDowntrend(testTime);
        C6ReversalCallStrategy strategy = new C6ReversalCallStrategy();

        boolean triggered = strategy.isTriggered("AAPL", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit at least one [C6] DEBUG line
        assertThat(logMessages)
                .as("Expected at least one [C6] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[C6]"));

        // Must contain ❌ STOP
        assertThat(logMessages)
                .as("Expected ❌ STOP for the failed condition")
                .anyMatch(msg -> msg.contains("❌ STOP"));

        // Format: Paso X/4 (4 total conditions)
        assertThat(logMessages)
                .as("Log line must contain 'Paso X/4' step counter (4 total conditions)")
                .anyMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        // Description must be quoted
        assertThat(logMessages)
                .as("Condition description must be enclosed in double quotes")
                .anyMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // Arrow separator → before the value
        assertThat(logMessages)
                .as("Log line must use → arrow before the condition value")
                .anyMatch(msg -> msg.contains(" → "));

        // Paso 1/4 specifically must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 1/4 line must contain ❌ STOP marker")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("❌ STOP"));

        // No Paso 2/4 or beyond — execution stopped at 1
        assertThat(logMessages)
                .as("No Paso 2/4 lines should appear — execution stopped at step 1")
                .noneMatch(msg -> msg.contains("Paso 2/4"));
    }

    @Test
    @DisplayName("When SMA20 cross fails (Paso 2), Paso 1 is ✅ and Paso 2 is ❌ STOP, no Paso 3")
    void whenCrossAboveSmAFails_logShowsStep1PassedAndStep2Stopped() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep2_NoCrossAboveSma(testTime);
        C6ReversalCallStrategy strategy = new C6ReversalCallStrategy();

        boolean triggered = strategy.isTriggered("SPY", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // [C6] lines emitted
        assertThat(logMessages)
                .as("Expected [C6] DEBUG log lines")
                .anyMatch(msg -> msg.startsWith("[C6]"));

        // Paso 1/4 must be ✅
        assertThat(logMessages)
                .as("Paso 1/4 must be ✅ (prior downtrend passed)")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("✅"));

        // Paso 2/4 must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 2/4 must be ❌ STOP (no cross above SMA20)")
                .anyMatch(msg -> msg.contains("Paso 2/4") && msg.contains("❌ STOP"));

        // No Paso 3/4
        assertThat(logMessages)
                .as("No Paso 3/4 lines should appear — execution stopped at step 2")
                .noneMatch(msg -> msg.contains("Paso 3/4"));

        // All [C6] lines use Paso X/4 format
        assertThat(logMessages)
                .as("All [C6] lines must use 'Paso X/4' format")
                .filteredOn(msg -> msg.startsWith("[C6]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        // All [C6] lines have quoted description
        assertThat(logMessages)
                .as("All [C6] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[C6]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // All [C6] lines use → separator
        assertThat(logMessages)
                .as("All [C6] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[C6]"))
                .allMatch(msg -> msg.contains(" → "));
    }
}
