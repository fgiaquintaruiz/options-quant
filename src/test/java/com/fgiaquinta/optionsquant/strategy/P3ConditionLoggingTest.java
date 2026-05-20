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
 * Verifies that P3BouncePutStrategy emits per-condition DEBUG log lines in the format:
 *   [P3] <ticker> @ <time> — Paso X/4 "<libro description>" → <value> ✅  (or ❌ STOP)
 *
 * P3 maps to 4 book steps (libro 3/4 — bearish mirror of C3):
 *   Paso 1/4 — "Tendencia bajista clara en Bollinger temporalidad hora"
 *   Paso 2/4 — "Precio acercándose a SMA20 diaria como punto de rebote bajista"
 *   Paso 3/4 — "Precio respeta el punto, en 15m comienza a rebotar a la baja"
 *   Paso 4/4 — "En hora, vela de confirmación bajista → entrada"
 */
class P3ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger p3Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        p3Logger = (Logger) LoggerFactory.getLogger(P3BouncePutStrategy.class);
        logAppender.start();
        p3Logger.addAppender(logAppender);
        p3Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (p3Logger != null && logAppender != null) {
            p3Logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // =========================================================================
    // Data builders
    // =========================================================================

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    /**
     * Builds StrategyData that causes P3 to FAIL at Paso 1/4 (no 1D downtrend).
     *
     * 1D: uptrend — close[i-1] > close[i-2] so isDowntrend = false → Paso 1 fails.
     * 1H / 15m: irrelevant (step 1 stops first).
     */
    private StrategyData buildDataThatFailsStep1_DailyTrend(ZonedDateTime currentTime) {
        // 1D: ascending prices — last close > previous close
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(25);
        for (int i = 0; i < 24; i++) {
            double price = 100.0 + i * 0.5; // strictly ascending
            daily.add(candle(dayBase.plusDays(i), price, price + 1, price - 1, price, 5000000L));
        }
        daily.add(candle(currentTime, 112.0, 113.0, 111.0, 112.5, 5000000L));

        // 1H: 25 flat bars (enough idx, irrelevant)
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        for (int i = 0; i < 25; i++) {
            hourly.add(candle(hourBase.plusHours(i), 110.0, 110.5, 109.5, 110.0, 1000000L));
        }

        // 15m: 25 flat bars (irrelevant)
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
     * Builds StrategyData that PASSES Paso 1–2 but FAILS at Paso 3/4 (15m downtrend not confirmed).
     *
     * 1D: downtrend (descending closes) — Paso 1 passes.
     * 1H:
     *   - 20 bars at 100.0 (anchors SMA20 = 100.0)
     *   - Bars 20..23: at 102.5 (above upper BB region — brokeAboveUpperBand = true)
     *   - Current bar (idx=24): high touches SMA20 zone, close below SMA20 — Paso 2/4 and 4/4 pass
     * 15m:
     *   - close15m > sma20_15m → confirmedDowntrend15m = false → Paso 3/4 fails
     */
    private StrategyData buildDataThatFailsStep3_15mReversal(ZonedDateTime currentTime) {
        // 1D: descending prices (25 bars)
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(26);
        for (int i = 0; i < 26; i++) {
            double price = 150.0 - i * 1.0; // strictly descending
            daily.add(candle(dayBase.plusDays(i), price + 0.5, price + 1, price - 1, price, 5000000L));
        }

        // 1H: 25 bars total (idx=24, >=20 ✓)
        // Bars 0..19: close = 100.0 (flat, anchors SMA20 ≈ 100.0, std ≈ 0, upper BB ≈ 100.0)
        // Bars 20..23: close = 102.5 (above upper band → brokeAboveUpperBand = true)
        // Bar 24 (current): high=100.4 ≥ SMA20*0.998, close=99.7 < SMA20 → Paso 2+4 pass
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        for (int i = 0; i < 20; i++) {
            hourly.add(candle(hourBase.plusHours(i), 100.0, 100.5, 99.5, 100.0, 1000000L));
        }
        for (int i = 20; i < 24; i++) {
            hourly.add(candle(hourBase.plusHours(i), 102.0, 103.0, 101.5, 102.5, 900000L));
        }
        // SMA20 at idx=24 (last 20 closes = bars[5..24]):
        //   bars 5..19: 15 × 100.0 = 1500; bars 20..23: 4 × 102.5 = 410; bar 24: 99.8
        //   sum = 2009.8 / 20 = 100.49
        // touchedResistance: high(100.4) >= 100.49*0.998 = 100.29 → TRUE ✓
        // rejectedResistance: close(99.8) < 100.49 → TRUE ✓
        hourly.add(candle(
                currentTime,
                100.5,  // open
                100.4,  // high — touches SMA20 zone
                99.3,   // low
                99.8,   // close — below SMA20 ✓
                1200000L
        ));

        // 15m: price above SMA20 → confirmedDowntrend15m = false → Paso 3/4 fails
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(25L * 15);
        for (int i = 0; i < 24; i++) {
            // Flat at 98.0 to anchor SMA20 near 98.0
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 98.0, 98.5, 97.5, 98.0, 500000L));
        }
        // Current 15m bar: close=100.5 > SMA20(≈98.0) → confirmedDowntrend15m = false → ❌ STOP
        candles15m.add(candle(currentTime, 99.0, 100.8, 98.8, 100.5, 600000L));

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
    @DisplayName("When daily downtrend condition fails (Paso 1), log contains [P3] prefix with Paso 1/4 and ❌ STOP")
    void whenDailyTrendFails_logContainsPaso1WithStopMarker() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep1_DailyTrend(testTime);
        P3BouncePutStrategy strategy = new P3BouncePutStrategy();

        boolean triggered = strategy.isTriggered("SPY", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit at least one [P3] line
        assertThat(logMessages)
                .as("Expected at least one [P3] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[P3]"));

        // Must contain ❌ STOP
        assertThat(logMessages)
                .as("Expected ❌ STOP for the failed condition")
                .anyMatch(msg -> msg.contains("❌ STOP"));

        // Format: Paso X/4 (4 total book steps)
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

        // Paso 1/4 must contain the exact libro label
        assertThat(logMessages)
                .as("Paso 1/4 must contain the libro label for step 1")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("Tendencia bajista clara en Bollinger temporalidad hora"));
    }

    @Test
    @DisplayName("When Paso 3/4 fails (15m reversal to the downside), steps 1-2 are ✅ and Paso 3/4 is ❌ STOP")
    void whenStep3_15mReversalFails_logShowsStep3AsStop() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep3_15mReversal(testTime);
        P3BouncePutStrategy strategy = new P3BouncePutStrategy();

        boolean triggered = strategy.isTriggered("NVDA", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit [P3] lines
        assertThat(logMessages)
                .as("Expected [P3] DEBUG log lines")
                .anyMatch(msg -> msg.startsWith("[P3]"));

        // Paso 3/4 must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 3/4 must be ❌ STOP (15m downtrend reversal not confirmed)")
                .anyMatch(msg -> msg.contains("Paso 3/4") && msg.contains("❌ STOP"));

        // No Paso 4/4 lines should appear — execution stopped at 3
        assertThat(logMessages)
                .as("No Paso 4/4 lines should appear — execution stopped at step 3")
                .noneMatch(msg -> msg.contains("Paso 4/4"));

        // Format checks on all P3 lines
        assertThat(logMessages)
                .as("All [P3] lines must use 'Paso X/4' format")
                .filteredOn(msg -> msg.startsWith("[P3]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        assertThat(logMessages)
                .as("All [P3] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[P3]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        assertThat(logMessages)
                .as("All [P3] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[P3]"))
                .allMatch(msg -> msg.contains(" → "));

        // Paso 3/4 must contain the exact libro label
        assertThat(logMessages)
                .as("Paso 3/4 must contain the libro label for step 3")
                .anyMatch(msg -> msg.contains("Paso 3/4") && msg.contains("Precio respeta el punto, en 15m comienza a rebotar a la baja"));
    }
}
