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
 * Verifies that C3BounceCallStrategy emits per-condition DEBUG log lines in the format:
 *   [C3] <ticker> @ <time> — Paso X/4 "<step description>" → <value> ✅  (or ❌ STOP)
 *
 * C3 maps to 4 steps:
 *   Paso 1/4 — "Tendencia clara en Bollinger temporalidad hora"
 *   Paso 2/4 — "Precio acercándose a SMA20 diaria como punto de rebote"
 *   Paso 3/4 — "Precio respeta el punto, en 15m comienza a rebotar"
 *   Paso 4/4 — "En hora, vela de confirmación → entrada"
 */
class C3ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger c3Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        c3Logger = (Logger) LoggerFactory.getLogger(C3BounceCallStrategy.class);
        logAppender.start();
        c3Logger.addAppender(logAppender);
        c3Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (c3Logger != null && logAppender != null) {
            c3Logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // =========================================================================
    // Data builders
    // =========================================================================

    /**
     * Builds StrategyData that causes C3 to FAIL at Paso 1/4 (no 1D uptrend).
     *
     * 1D: downtrend — close[i-1] < close[i-2] so isUptrend = false → Paso 1 fails.
     * 1H / 15m: irrelevant (step 1 stops first).
     */
    private StrategyData buildDataThatFailsStep1_DailyTrend(ZonedDateTime currentTime) {
        // 1D: descending prices so last close < previous close
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(25);
        for (int i = 0; i < 24; i++) {
            double price = 150.0 - i * 0.5; // strictly descending
            daily.add(candle(dayBase.plusDays(i), price + 0.5, price + 1, price - 1, price, 5000000L));
        }
        daily.add(candle(currentTime, 138.0, 139.0, 137.0, 138.0, 5000000L));

        // 1H: 25 flat bars (enough idx, irrelevant)
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        for (int i = 0; i < 25; i++) {
            hourly.add(candle(hourBase.plusHours(i), 100.0, 100.5, 99.5, 100.0, 1000000L));
        }

        // 15m: 25 flat bars (irrelevant)
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
     * Builds StrategyData that PASSES Paso 1 but FAILS at Paso 3/4 (15m reversal not confirmed).
     *
     * 1D: uptrend (ascending close prices) — Paso 1 daily part passes.
     * 1H:
     *   - 20 bars at 100.0 (anchors SMA20 = 100.0)
     *   - Bar 20..22: at 100.5 (slightly above SMA20)
     *   - Current bar (idx=23): low touches SMA20 zone, close above SMA20 — Paso 2/4 and 4/4 pass
     *   - BB context: brokeBelowLowerBand window — set via bar values at lower band boundary
     * 15m:
     *   - close15m < sma20_15m → confirmedUptrend15m = false → Paso 3/4 fails
     *
     * Note: C3's current logic has only 3 technical checks mapped to 4 steps.
     * Paso 3 = 15m above SMA20, Paso 4 = 1H bullish candle (touchedSupport + rejectedSupport).
     * We arrange data so: 1D uptrend ✅, BB context ✅, Paso 2 ✅, but 15m below SMA20 → Paso 3 ❌.
     */
    private StrategyData buildDataThatFailsStep3_15mReversal(ZonedDateTime currentTime) {
        // 1D: ascending prices (24 bars + current)
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(25);
        for (int i = 0; i < 24; i++) {
            double price = 100.0 + i * 0.5;
            daily.add(candle(dayBase.plusDays(i), price, price + 1, price - 1, price, 5000000L));
        }
        daily.add(candle(currentTime, 112.0, 113.0, 111.0, 112.5, 5000000L));

        // 1H: 25 bars total (idx=24, >=20 ✓)
        // Bars 0..19: close = 100.0 (anchors SMA20)
        // Bars 20..22: close = 98.5 (below lower BB region — brokeBelowLowerBand will see this)
        // Bar 23: close = 99.0 (below SMA20 to fail BB context if needed)
        // Bar 24 (current): low=99.8, close=100.5 > sma20≈99.6 → Paso 2+4 pass
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        for (int i = 0; i < 20; i++) {
            hourly.add(candle(hourBase.plusHours(i), 100.0, 100.5, 99.5, 100.0, 1000000L));
        }
        // Several bars clearly below lower BB (SMA20 - 2*std). With flat 100.0 bars, std is near 0,
        // so lower band ≈ 100.0. Bars at 97.0 will be well below → brokeBelowLowerBand = true.
        for (int i = 20; i < 24; i++) {
            hourly.add(candle(hourBase.plusHours(i), 97.5, 98.0, 97.0, 97.5, 800000L));
        }
        // Current bar: low touches SMA20 zone, close above SMA20 → touchedSupport=true, rejectedSupport=true
        // SMA20 at idx=24 (last 20 closes = bars[5..24]):
        //   bars 5..19: 15 × 100.0 = 1500; bars 20..23: 4 × 97.5 = 390; bar 24: 100.3 → sum/20 ≈ 99.5
        hourly.add(candle(
                currentTime,
                99.0,   // open
                101.0,  // high
                99.0,   // low — touches SMA20 zone (~99.5 * 1.002 = 99.7, low=99.0 ≤ 99.7 ✓)
                100.3,  // close — above SMA20 (~99.5) ✓
                1200000L
        ));

        // 15m: price below SMA20 → confirmedUptrend15m = false → Paso 3/4 fails
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(25L * 15);
        for (int i = 0; i < 24; i++) {
            // Flat at 102.0 to anchor SMA20 near 102.0
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 102.0, 102.5, 101.5, 102.0, 500000L));
        }
        // Current 15m bar: close=100.0 < SMA20(≈102.0) → confirmedUptrend15m = false → ❌ STOP
        candles15m.add(candle(currentTime, 101.0, 101.5, 99.5, 100.0, 600000L));

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
    @DisplayName("When daily uptrend condition fails (Paso 1), log contains [C3] prefix with Paso 1/4 and ❌ STOP")
    void whenDailyTrendFails_logContainsPaso1WithStopMarker() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep1_DailyTrend(testTime);
        C3BounceCallStrategy strategy = new C3BounceCallStrategy();

        boolean triggered = strategy.isTriggered("AAPL", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit at least one [C3] line
        assertThat(logMessages)
                .as("Expected at least one [C3] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[C3]"));

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

        // Paso 1/4 specifically must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 1/4 line must contain ❌ STOP marker")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("❌ STOP"));

        // Paso 1/4 must contain the exact step label
        assertThat(logMessages)
                .as("Paso 1/4 must contain the step label for step 1")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("Tendencia clara en Bollinger temporalidad hora"));
    }

    @Test
    @DisplayName("When Paso 3/4 fails (15m reversal), steps 1-2 are ✅ and Paso 3/4 is ❌ STOP")
    void whenStep3_15mReversalFails_logShowsStep3AsStop() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep3_15mReversal(testTime);
        C3BounceCallStrategy strategy = new C3BounceCallStrategy();

        boolean triggered = strategy.isTriggered("TSLA", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit [C3] lines
        assertThat(logMessages)
                .as("Expected [C3] DEBUG log lines")
                .anyMatch(msg -> msg.startsWith("[C3]"));

        // Paso 3/4 must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 3/4 must be ❌ STOP (15m reversal not confirmed)")
                .anyMatch(msg -> msg.contains("Paso 3/4") && msg.contains("❌ STOP"));

        // No Paso 4/4 lines should appear — execution stopped at 3
        assertThat(logMessages)
                .as("No Paso 4/4 lines should appear — execution stopped at step 3")
                .noneMatch(msg -> msg.contains("Paso 4/4"));

        // Format checks on all C3 lines
        assertThat(logMessages)
                .as("All [C3] lines must use 'Paso X/4' format")
                .filteredOn(msg -> msg.startsWith("[C3]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        assertThat(logMessages)
                .as("All [C3] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[C3]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        assertThat(logMessages)
                .as("All [C3] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[C3]"))
                .allMatch(msg -> msg.contains(" → "));

        // Paso 3/4 must contain the exact step label
        assertThat(logMessages)
                .as("Paso 3/4 must contain the step label for step 3")
                .anyMatch(msg -> msg.contains("Paso 3/4") && msg.contains("Precio respeta el punto, en 15m comienza a rebotar"));
    }
}
