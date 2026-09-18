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
 * Verifies that P4OpeningPutStrategy emits per-condition DEBUG log lines in the format:
 *   [P4] <ticker> @ <time> — Paso X/3 "<step description>" → <value> ✅  (or ❌ STOP)
 *
 * P4 maps to 3 steps:
 *   Paso 1/3 — "Tendencia totalmente lateral y sin volatilidad en 15m"
 *   Paso 2/3 — "Apertura con salto, precio en zona de sobreventa"
 *   Paso 3/3 — "Ejecutar en los primeros 5 minutos de apertura"
 */
class P4ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger p4Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        p4Logger = (Logger) LoggerFactory.getLogger(P4OpeningPutStrategy.class);
        logAppender.start();
        p4Logger.addAppender(logAppender);
        p4Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (p4Logger != null && logAppender != null) {
            p4Logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    /**
     * Builds data where Paso 1 (lateral BB) passes but Paso 2 (gap up above upper BB) fails.
     * The 15m series is lateral (BB width < 2%), but today's open is at the same level as
     * yesterday's close (no gap up), so isExtremeGapUp is false → Paso 2 fails.
     *
     * Time is 9:30 NY so the time gate does NOT block first.
     */
    private StrategyData buildDataThatFailsStep2_NoGapUp(ZonedDateTime currentTime) {
        double price = 100.0;

        // 15m: 22 flat candles → BB width ≈ 0 → isLateral passes
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(21L * 15);
        for (int i = 0; i < 21; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), price, price + 0.1, price - 0.1, price, 500000L));
        }
        candles15m.add(candle(currentTime, price, price + 0.1, price - 0.1, price, 500000L));

        // 5m: yesterday close at 100, today open at 100 (no gap up — not above upper BB)
        ZonedDateTime m5YesterdayClose = currentTime.minusMinutes(5);
        List<Candle> candles5m = new ArrayList<>();
        candles5m.add(candle(m5YesterdayClose, price, price + 0.5, price - 0.5, price, 500000L));
        candles5m.add(candle(currentTime, price, price + 0.5, price - 0.5, price - 0.2, 500000L)); // red candle but no gap up

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.MIN_15, candles15m);
        data.put(TimeFrame.MIN_5, candles5m);
        return new StrategyData(data);
    }

    /**
     * Builds data where all 3 steps pass.
     * Time: 9:30 NY → Paso 3 passes.
     * 15m BB lateral (flat closes → width ≈ 0, < 2%) → Paso 1 passes.
     * 5m: today opens +3% above yesterday close → above upper BB (upper BB ≈ 100 when all flat).
     * 5m: today close < today open (red candle) → Paso 3 passes.
     * Gap: +3% → between +1.5% and +6% → range filter passes.
     */
    private StrategyData buildDataThatTriggersAllSteps(ZonedDateTime currentTime) {
        double yesterdayClose = 100.0;
        double todayOpen = yesterdayClose * 1.03; // +3% gap up (extreme)

        // 15m: 22 flat candles so BB width ≈ 0 → isLateral passes
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(21L * 15);
        for (int i = 0; i < 21; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i),
                    yesterdayClose, yesterdayClose + 0.1, yesterdayClose - 0.1, yesterdayClose, 500000L));
        }
        candles15m.add(candle(currentTime, todayOpen, todayOpen + 0.1, todayOpen - 0.1, todayOpen, 500000L));

        // 5m: yesterday close bar + today open (red: close < open)
        ZonedDateTime m5YesterdayClose = currentTime.minusMinutes(5);
        List<Candle> candles5m = new ArrayList<>();
        candles5m.add(candle(m5YesterdayClose, yesterdayClose, yesterdayClose + 0.5, yesterdayClose - 0.5, yesterdayClose, 500000L));
        double todayClose = todayOpen - 0.5; // red candle
        candles5m.add(candle(currentTime, todayOpen, todayOpen + 0.1, todayClose - 0.1, todayClose, 1000000L));

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.MIN_15, candles15m);
        data.put(TimeFrame.MIN_5, candles5m);
        return new StrategyData(data);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("When step-2 (gap up) fails, log shows Paso 2/3 as ❌ STOP with [P4] prefix")
    void whenStep2GapFails_logShowsStep2AsStop() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 30, 0, 0, NY);
        StrategyData data = buildDataThatFailsStep2_NoGapUp(testTime);
        P4OpeningPutStrategy strategy = new P4OpeningPutStrategy();

        boolean triggered = strategy.isTriggered("SPY", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // At least one [P4] line must be emitted
        assertThat(logMessages)
                .as("Expected at least one [P4] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[P4]"));

        // Paso 2/3 must show ❌ STOP
        assertThat(logMessages)
                .as("Paso 2/3 must be ❌ STOP (gap up check failed)")
                .anyMatch(msg -> msg.contains("Paso 2/3") && msg.contains("❌ STOP"));

        // Paso 2/3 must contain the step label
        assertThat(logMessages)
                .as("Paso 2/3 must contain the step label")
                .anyMatch(msg -> msg.contains("Paso 2/3") && msg.contains("Apertura con salto, precio en zona de sobreventa"));

        // Paso 3/3 must NOT appear (execution stopped at step 2)
        assertThat(logMessages)
                .as("No Paso 3/3 lines should appear — stopped at step 2")
                .noneMatch(msg -> msg.contains("Paso 3/3"));

        // Format: Paso X/3 (3 steps)
        assertThat(logMessages)
                .as("Log lines must contain 'Paso X/3' step counter")
                .anyMatch(msg -> msg.matches(".*Paso \\d/3.*"));

        // Description must be in double quotes
        assertThat(logMessages)
                .as("Condition description must be enclosed in double quotes")
                .anyMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // Arrow separator → must be present
        assertThat(logMessages)
                .as("Log line must use → arrow before the condition value")
                .anyMatch(msg -> msg.contains(" → "));

        // All [P4] lines must use Paso X/3 format
        assertThat(logMessages)
                .as("All [P4] lines must use 'Paso X/3' format")
                .filteredOn(msg -> msg.startsWith("[P4]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/3.*"));

        // All [P4] lines must have → arrow
        assertThat(logMessages)
                .as("All [P4] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[P4]"))
                .allMatch(msg -> msg.contains(" → "));
    }

    @Test
    @DisplayName("When all conditions pass, exactly 3 [P4] log lines each with ✅")
    void whenAllConditionsPass_threeLogLinesEachWithCheckmark() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 9, 30, 0, 0, NY);
        StrategyData data = buildDataThatTriggersAllSteps(testTime);
        P4OpeningPutStrategy strategy = new P4OpeningPutStrategy();

        boolean triggered = strategy.isTriggered("SPY", data, testTime);

        assertThat(triggered).isTrue();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(logMessages)
                .as("Expected at least one [P4] DEBUG log line when strategy triggers")
                .anyMatch(msg -> msg.startsWith("[P4]"));

        assertThat(logMessages)
                .as("No ❌ lines should appear when strategy triggers successfully")
                .noneMatch(msg -> msg.startsWith("[P4]") && msg.contains("❌"));

        // Exactly 3 [P4] log lines
        assertThat(logMessages)
                .as("Exactly 3 [P4] log lines expected (one per step)")
                .filteredOn(msg -> msg.startsWith("[P4]"))
                .hasSize(3);

        // All lines must contain ✅
        assertThat(logMessages)
                .as("All [P4] lines should contain ✅ when strategy triggers")
                .filteredOn(msg -> msg.startsWith("[P4]"))
                .allMatch(msg -> msg.contains("✅"));

        // All lines must use Paso X/3 format
        assertThat(logMessages)
                .as("All [P4] lines must contain 'Paso X/3' step counter")
                .filteredOn(msg -> msg.startsWith("[P4]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/3.*"));

        // All lines must have quoted descriptions
        assertThat(logMessages)
                .as("All [P4] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[P4]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));
    }
}
