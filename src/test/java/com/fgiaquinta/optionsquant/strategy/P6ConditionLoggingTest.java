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
 * Verifies that P6ReversalPutStrategy emits per-condition DEBUG log lines in the format:
 *   [P6] <ticker> @ <time> — Paso X/4 "<description>" → <value> ✅  (or ❌ STOP)
 *
 * P6 has 4 conditions derived from the code logic:
 *   Paso 1/4 — "Tendencia alcista previa en 1H"
 *   Paso 2/4 — "Ruptura bajista de SMA20 en 1H con vela roja"
 *   Paso 3/4 — "Cierre en tercio inferior y volumen suficiente"
 *   Paso 4/4 — "Confirmación de tendencia bajista en 15m"
 */
class P6ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger p6Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        p6Logger = (Logger) LoggerFactory.getLogger(P6ReversalPutStrategy.class);
        logAppender.start();
        p6Logger.addAppender(logAppender);
        p6Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (p6Logger != null && logAppender != null) {
            p6Logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // =========================================================================
    // Data builders
    // =========================================================================

    /**
     * Builds StrategyData that FAILS at Paso 1/4 (no prior uptrend).
     *
     * 1H: 25 bars, last 3 prior closes are BELOW SMA20 → wasClearUptrend = false.
     * - Bars 0–19: close = 100.0 (anchor SMA20 = 100.0)
     * - Bars 20–22: close = 98.0 (below SMA20 → no prior uptrend)
     * - Current bar: bearish candle below SMA20
     */
    private StrategyData buildDataThatFailsStep1_NoUptrend(ZonedDateTime currentTime) {
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        // 20 anchor bars
        for (int i = 0; i < 20; i++) {
            hourly.add(candle(hourBase.plusHours(i), 100.0, 100.5, 99.5, 100.0, 1000000L));
        }
        // 3 bars BELOW SMA20 (no prior uptrend — fails step 1)
        for (int i = 20; i < 23; i++) {
            hourly.add(candle(hourBase.plusHours(i), 98.5, 99.0, 97.5, 98.0, 1000000L));
        }
        // Current bar: bearish candle
        hourly.add(candle(currentTime, 99.0, 99.5, 96.5, 97.0, 2000000L));

        // 1D: 25 flat bars
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
     * Builds StrategyData that PASSES Paso 1 but FAILS at Paso 2/4 (close stays above SMA20).
     *
     * 1H setup:
     *   - Bars 0–19: close = 98.0 (SMA20 anchor ≈ 98.0)
     *   - Bars 20–22: close = 101.0 (above SMA20 → wasClearUptrend passes)
     *   - Current bar (idx=23):
     *       close = 99.5, open = 98.0 → bullish (close > open) AND close > SMA20 → crossedBelowSma = false
     *
     * SMA20 at idx=23: bars[4..23] = 16×98 + 3×101 + 99.5 = 1568 + 303 + 99.5 = 1970.5 / 20 = 98.525
     * crossedBelowSma: 99.5 < 98.525 → FALSE → Paso 2 fails.
     */
    private StrategyData buildDataThatFailsStep2_NoCrossBelowSma(ZonedDateTime currentTime) {
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        // 20 anchor bars below
        for (int i = 0; i < 20; i++) {
            hourly.add(candle(hourBase.plusHours(i), 98.0, 98.5, 97.5, 98.0, 1000000L));
        }
        // 3 bars ABOVE SMA20 (prior uptrend — Paso 1 passes)
        for (int i = 20; i < 23; i++) {
            hourly.add(candle(hourBase.plusHours(i), 100.5, 101.5, 100.0, 101.0, 1000000L));
        }
        // Current bar: close still ABOVE SMA20 → crossedBelowSma = false → Paso 2 FAILS
        hourly.add(candle(currentTime, 98.0, 100.0, 97.5, 99.5, 2000000L));

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
    @DisplayName("When prior uptrend condition fails (Paso 1), log contains [P6] prefix, Paso 1/4, and ❌ STOP")
    void whenUptrendFails_logContainsPaso1WithStopMarker() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep1_NoUptrend(testTime);
        P6ReversalPutStrategy strategy = new P6ReversalPutStrategy();

        boolean triggered = strategy.isTriggered("AAPL", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit at least one [P6] DEBUG line
        assertThat(logMessages)
                .as("Expected at least one [P6] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[P6]"));

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

        // No Paso 2/4 — execution stopped at 1
        assertThat(logMessages)
                .as("No Paso 2/4 lines should appear — execution stopped at step 1")
                .noneMatch(msg -> msg.contains("Paso 2/4"));
    }

    @Test
    @DisplayName("When SMA20 cross-below fails (Paso 2), Paso 1 is ✅ and Paso 2 is ❌ STOP, no Paso 3")
    void whenCrossBelowSmsFails_logShowsStep1PassedAndStep2Stopped() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep2_NoCrossBelowSma(testTime);
        P6ReversalPutStrategy strategy = new P6ReversalPutStrategy();

        boolean triggered = strategy.isTriggered("SPY", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // [P6] lines emitted
        assertThat(logMessages)
                .as("Expected [P6] DEBUG log lines")
                .anyMatch(msg -> msg.startsWith("[P6]"));

        // Paso 1/4 must be ✅
        assertThat(logMessages)
                .as("Paso 1/4 must be ✅ (prior uptrend passed)")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("✅"));

        // Paso 2/4 must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 2/4 must be ❌ STOP (no cross below SMA20)")
                .anyMatch(msg -> msg.contains("Paso 2/4") && msg.contains("❌ STOP"));

        // No Paso 3/4
        assertThat(logMessages)
                .as("No Paso 3/4 lines should appear — execution stopped at step 2")
                .noneMatch(msg -> msg.contains("Paso 3/4"));

        // All [P6] lines use Paso X/4 format
        assertThat(logMessages)
                .as("All [P6] lines must use 'Paso X/4' format")
                .filteredOn(msg -> msg.startsWith("[P6]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        // All [P6] lines have quoted description
        assertThat(logMessages)
                .as("All [P6] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[P6]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // All [P6] lines use → separator
        assertThat(logMessages)
                .as("All [P6] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[P6]"))
                .allMatch(msg -> msg.contains(" → "));
    }
}
