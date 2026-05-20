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
 * Verifies that C5ContinuationCallStrategy emits per-condition DEBUG log lines in the format:
 *   [C5] <ticker> @ <time> — Paso X/4 "<libro description>" → <value> ✅  (or ❌ STOP)
 *
 * C5 maps to 4 reference book steps:
 *   Paso 1/4 — "Tendencia clara llevando varios días"
 *   Paso 2/4 — "Apertura con fuerte salto, precio alejado de SMA20"
 *   Paso 3/4 — "Primera vela 15m completamente fuera del Bollinger"
 *   Paso 4/4 — "Vela cruza línea roja del Worden Stochastics → entrada"
 */
class C5ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger c5Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        c5Logger = (Logger) LoggerFactory.getLogger(C5ContinuationCallStrategy.class);
        logAppender.start();
        c5Logger.addAppender(logAppender);
        c5Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (c5Logger != null && logAppender != null) {
            c5Logger.detachAppender(logAppender);
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
     * Builds StrategyData where:
     * - Paso 1 (bearish daily trend): PASSES — 3 consecutive red daily candles
     * - Paso 2 (gap down + far from SMA20): PASSES — open well below yesterday's close and 3% below SMA20
     * - Paso 3 (BB outside): FAILS — first 15m high is ABOVE the lower BB (candle not fully outside)
     *
     * Time: 9:50 AM NY (inside the 9:45–9:55 window)
     */
    private StrategyData buildDataThatFailsStep3_BollingerOutside(ZonedDateTime currentTime) {
        // --- DAY_1: bearish trend (3 consecutive red candles) ---
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = currentTime.minusDays(6);
        // 3 setup bars descending, then the current day bar
        daily.add(candle(dayBase,             105.0, 106.0, 104.0, 105.0, 5000000L));
        daily.add(candle(dayBase.plusDays(1), 104.0, 104.5, 102.5, 103.0, 5000000L)); // red
        daily.add(candle(dayBase.plusDays(2), 103.0, 103.5, 101.0, 102.0, 5000000L)); // red
        daily.add(candle(dayBase.plusDays(3), 102.0, 102.5, 100.0, 101.0, 5000000L)); // red (prevClose1D = 101.0)
        daily.add(candle(currentTime,          99.0, 100.0,  98.0,  99.0, 6000000L)); // current day (gapped down)

        // --- HOUR_1: 25 bars so idx1h >= 20; SMA20 around 103.0 (price was 103–105 range) ---
        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(25);
        for (int i = 0; i < 24; i++) {
            double price = 103.0;
            hourly.add(candle(hourBase.plusHours(i), price, price + 0.5, price - 0.5, price, 1000000L));
        }
        // Current 1H bar (at 9:45 window, open reflects the gap down)
        hourly.add(candle(currentTime, 99.0, 99.5, 98.5, 99.0, 2000000L));

        // --- MIN_15: 22 bars so idx15m >= 20 ---
        // 20 lateral bars around 103.0 (these establish the Bollinger Bands with std dev ~0)
        // Then the "first 15m candle" (idx = 20, i.e. firstCandle15mIdx = idx15m - 1):
        //   - Open below prevClose1D (101.0) → gap down passes
        //   - Open well below SMA20 (~103) by >3% → 99.0 < 103.0 * 0.97 = 99.91 → passes
        //   - High = 100.5 — we need this ABOVE the lower BB to FAIL step 3
        //     Lower BB ≈ 103.0 - 2*0 ≈ 103.0 (lateral data), so high(100.5) < 103.0 → that would PASS
        //
        // To make step 3 FAIL: we need first15mHigh >= lowerBand15m.
        // Use lateral data slightly below the BB level so the lower band is around 99.0,
        // then make the candle high = 100.0 which would be above the band.
        // Strategy: 20 bars at price=99.0 (std dev=0, mean=99, lower BB=99), first candle high=100.0 >= 99.0 → FAIL

        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(22L * 15);
        for (int i = 0; i < 20; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 99.0, 99.5, 98.5, 99.0, 500000L));
        }
        // First 15m candle (9:30–9:45): gap down, but high > lowerBand → step 3 FAILS
        // open=97.5 (below prevClose1D=101 → gap passes), open=97.5 < 103*0.97=99.91 → step 2 passes
        // high=100.0: BB lower ≈ 99.0 - 2*std ≈ 99.0 (no std), high(100) >= lowerBand(99.0) → FAILS step 3 ✓
        candles15m.add(candle(m15Base.plusMinutes(20L * 15), 97.5, 100.0, 96.5, 98.0, 3000000L));
        // Current 15m candle (9:45+): reversal up
        candles15m.add(candle(currentTime, 98.0, 99.5, 97.5, 99.0, 2000000L));

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
    @DisplayName("When Bollinger-outside condition fails (Paso 3), log shows Paso 3/4 as ❌ STOP")
    void whenBollingerOutsideFails_logShowsStep3AsStop() {
        // 9:50 AM NY — inside the C5 entry window (9:45–9:55)
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep3_BollingerOutside(testTime);
        C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();

        boolean triggered = strategy.isTriggered("TEST", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit at least one [C5] line
        assertThat(logMessages)
                .as("Expected at least one [C5] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[C5]"));

        // Must contain ❌ STOP for the failing step
        assertThat(logMessages)
                .as("Expected a ❌ STOP log line for the failed condition")
                .anyMatch(msg -> msg.contains("❌ STOP"));

        // Format: Paso X/4 (4 total book steps)
        assertThat(logMessages)
                .as("Log line must contain 'Paso X/4' step counter (4 total conditions)")
                .anyMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        // Description must be quoted with double quotes
        assertThat(logMessages)
                .as("Condition description must be enclosed in double quotes")
                .anyMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // Arrow separator → must precede the actual value
        assertThat(logMessages)
                .as("Log line must use → arrow before the condition value")
                .anyMatch(msg -> msg.contains(" → "));

        // All [C5] lines must use Paso X/4 format
        assertThat(logMessages)
                .as("All [C5] lines must use 'Paso X/4' format")
                .filteredOn(msg -> msg.startsWith("[C5]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/4.*"));

        // All [C5] lines must have double-quoted description
        assertThat(logMessages)
                .as("All [C5] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[C5]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // All [C5] lines must use → arrow separator
        assertThat(logMessages)
                .as("All [C5] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[C5]"))
                .allMatch(msg -> msg.contains(" → "));

        // Paso 1 and 2 must be ✅ (they passed before step 3 failed)
        assertThat(logMessages)
                .as("Paso 1/4 must be ✅ (bearish trend passed)")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("✅"));

        assertThat(logMessages)
                .as("Paso 2/4 must be ✅ (gap down + SMA distance passed)")
                .anyMatch(msg -> msg.contains("Paso 2/4") && msg.contains("✅"));

        // Paso 3 must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 3/4 must be ❌ STOP (BB outside check failed)")
                .anyMatch(msg -> msg.contains("Paso 3/4") && msg.contains("❌ STOP"));

        // No Paso 4/4 lines — execution stopped at step 3
        assertThat(logMessages)
                .as("No Paso 4/4 lines should appear — execution stopped at step 3")
                .noneMatch(msg -> msg.contains("Paso 4/4"));

        // Paso 3 label must contain the libro description
        assertThat(logMessages)
                .as("Paso 3/4 must contain the libro step label about Bollinger")
                .anyMatch(msg -> msg.contains("Paso 3/4") && msg.contains("Primera vela 15m completamente fuera del Bollinger"));
    }

    @Test
    @DisplayName("When bearish-trend condition fails (Paso 1), first log line is ❌ STOP with libro label")
    void whenBearishTrendFails_firstLogLineIsStopWithLibroLabel() {
        // 9:50 AM NY — inside the C5 entry window
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 50, 0, 0, NY);

        // Build data with a BULLISH daily trend (ascending) → Paso 1 must FAIL
        List<Candle> daily = new ArrayList<>();
        ZonedDateTime dayBase = testTime.minusDays(5);
        daily.add(candle(dayBase,             100.0, 101.0, 99.0, 100.0, 5000000L));
        daily.add(candle(dayBase.plusDays(1), 101.0, 102.0, 100.0, 101.0, 5000000L)); // green
        daily.add(candle(dayBase.plusDays(2), 102.0, 103.0, 101.0, 102.0, 5000000L)); // green
        daily.add(candle(dayBase.plusDays(3), 103.0, 104.0, 102.0, 103.0, 5000000L)); // green (prevClose=103)
        daily.add(candle(testTime,            104.0, 105.0, 103.0, 104.0, 5000000L));

        List<Candle> hourly = new ArrayList<>();
        ZonedDateTime hourBase = testTime.minusHours(25);
        for (int i = 0; i <= 24; i++) {
            hourly.add(candle(hourBase.plusHours(i), 103.0, 103.5, 102.5, 103.0, 1000000L));
        }

        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = testTime.minusMinutes(22L * 15);
        for (int i = 0; i <= 21; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 103.0, 103.5, 102.5, 103.0, 500000L));
        }

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.DAY_1, daily);
        data.put(TimeFrame.HOUR_1, hourly);
        data.put(TimeFrame.MIN_15, candles15m);
        StrategyData strategyData = new StrategyData(data);

        C5ContinuationCallStrategy strategy = new C5ContinuationCallStrategy();
        boolean triggered = strategy.isTriggered("TEST", strategyData, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must emit at least one [C5] line
        assertThat(logMessages)
                .as("Expected at least one [C5] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[C5]"));

        // Paso 1/4 must be ❌ STOP
        assertThat(logMessages)
                .as("Paso 1/4 must be ❌ STOP (bullish trend does not pass bearish check)")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("❌ STOP"));

        // Paso 1 label must contain the libro text
        assertThat(logMessages)
                .as("Paso 1/4 must contain the libro label about multi-day trend")
                .anyMatch(msg -> msg.contains("Paso 1/4") && msg.contains("Tendencia clara llevando varios días"));

        // No Paso 2/4 or beyond — stopped at step 1
        assertThat(logMessages)
                .as("No Paso 2/4 lines should appear — execution stopped at step 1")
                .noneMatch(msg -> msg.contains("Paso 2/4"));
    }
}
