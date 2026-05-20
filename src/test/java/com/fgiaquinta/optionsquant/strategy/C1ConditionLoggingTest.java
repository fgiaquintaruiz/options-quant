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
 * TDD — RED test written BEFORE adding @Slf4j and ConditionEvaluator to C1SqueezeCallStrategy.
 *
 * Verifies that C1SqueezeCallStrategy emits per-condition DEBUG log lines in the format:
 *   [C1] <ticker> @ <time> — Paso X/6 "<description>" → <value> ✅  (or ❌ STOP)
 *
 * The logger name is C1SqueezeCallStrategy (Lombok @Slf4j convention).
 */
class C1ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger c1Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        c1Logger = (Logger) LoggerFactory.getLogger(C1SqueezeCallStrategy.class);
        logAppender.start();
        c1Logger.addAppender(logAppender);
        c1Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (c1Logger != null && logAppender != null) {
            c1Logger.detachAppender(logAppender);
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
     * Builds StrategyData that causes C1 to FAIL at the SMA-spread condition.
     * Strong uptrend: SMAs will be far apart (NOT laterally compressed).
     */
    private StrategyData buildDataThatFailsSmaSpread(ZonedDateTime currentTime) {
        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        // Strong uptrend: prices rise from 80 to 200 over 280 bars — SMAs spread far apart
        for (int i = 0; i < totalBars - 1; i++) {
            double price = 80.0 + (i * 0.43);
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    price, price + 0.5, price - 0.5, price,
                    1000000L
            ));
        }
        // Last bar: bullish candle above imaginary ceiling
        double lastPrice = 200.0;
        hourlyCandles.add(candle(
                currentTime,
                lastPrice - 1.0,
                lastPrice + 1.5,
                lastPrice - 1.5,
                lastPrice,
                2000000L
        ));

        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(20L * 15);
        for (int i = 0; i < 20; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 100, 101, 99, 100, 500000L));
        }
        candles15m.add(candle(currentTime, 199.0, 201.5, 198.5, 200.5, 2000000L));

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourlyCandles);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    /**
     * Builds StrategyData where C1 fires fully (all conditions pass).
     * Lateral channel with bullish breakout above the 10-day ceiling.
     */
    private StrategyData buildDataThatTriggers(ZonedDateTime currentTime) {
        double ceiling = 100.0;
        double breakoutClose = ceiling * 1.004; // 0.4% above ceiling — clears buffer
        double breakoutOpen = ceiling - 1.0;

        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        // Lateral channel: prices oscillate tightly around ceiling — SMAs compressed
        for (int i = 0; i < totalBars - 1; i++) {
            double base = ceiling - 1.0 + (i % 3) * 0.2 - 0.1;
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    base, ceiling, base - 0.5, base,
                    2000000L
            ));
        }
        // Breakout bar: green candle closing above ceiling + buffer
        hourlyCandles.add(candle(
                currentTime,
                breakoutOpen, breakoutClose + 0.1, breakoutOpen - 0.1, breakoutClose,
                2000000L
        ));

        // 15m candles: 20 lateral + 1 riding upper BB with bullish body
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(20L * 15);
        for (int i = 0; i < 20; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 100, 100.5, 99.5, 100, 500000L));
        }
        candles15m.add(candle(
                currentTime,
                breakoutOpen, breakoutClose + 0.1, breakoutOpen - 0.1, breakoutClose,
                3000000L
        ));

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourlyCandles);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("When SMA spread condition fails, log contains [C1] prefix, step counter, quoted description, and ❌ STOP")
    void whenSmaSpreadFails_logContainsStepFormatWithQuotedDescriptionAndActualValue() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsSmaSpread(testTime);
        C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy();

        boolean triggered = strategy.isTriggered("TEST", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must contain at least one line with [C1] prefix
        assertThat(logMessages)
                .as("Expected at least one [C1] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[C1]"));

        // The failing condition must include ❌ STOP
        assertThat(logMessages)
                .as("Expected a ❌ STOP log line for the failed condition")
                .anyMatch(msg -> msg.contains("❌ STOP"));

        // Format: [C1] TEST @ 14:00 — Paso 1/6 "description" → value ❌ STOP
        assertThat(logMessages)
                .as("Log line must contain 'Paso X/6' step counter (6 total conditions)")
                .anyMatch(msg -> msg.matches(".*Paso \\d/6.*"));

        // The description must be quoted with double quotes
        assertThat(logMessages)
                .as("Condition description must be enclosed in double quotes")
                .anyMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // Arrow separator → must precede the actual value
        assertThat(logMessages)
                .as("Log line must use → arrow before the condition value")
                .anyMatch(msg -> msg.contains(" → "));

        // The description must contain an actual numeric value after the arrow
        assertThat(logMessages)
                .as("Condition description must include the computed value after →")
                .anyMatch(msg -> msg.contains("❌") && msg.contains(" → ") && msg.matches(".*\\d+\\.\\d+.*"));
    }

    @Test
    @DisplayName("When all conditions pass, every log line has Paso X/6 format with quoted description and ✅")
    void whenAllConditionsPass_allLogLinesHaveStepFormatAndCheckmark() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatTriggers(testTime);
        C1SqueezeCallStrategy strategy = new C1SqueezeCallStrategy(0.003);

        boolean triggered = strategy.isTriggered("TEST", data, testTime);

        assertThat(triggered).isTrue();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(logMessages)
                .as("Expected at least one [C1] DEBUG log line when strategy triggers")
                .anyMatch(msg -> msg.startsWith("[C1]"));

        assertThat(logMessages)
                .as("No ❌ lines should appear when strategy triggers successfully")
                .noneMatch(msg -> msg.startsWith("[C1]") && msg.contains("❌"));

        // All 6 conditions must appear, each with Paso N/6 format
        assertThat(logMessages)
                .as("All [C1] lines must contain 'Paso X/6' step counter")
                .filteredOn(msg -> msg.startsWith("[C1]"))
                .allMatch(msg -> msg.matches(".*Paso \\d/6.*"));

        // All lines must use double-quoted descriptions
        assertThat(logMessages)
                .as("All [C1] lines must have description in double quotes")
                .filteredOn(msg -> msg.startsWith("[C1]"))
                .allMatch(msg -> msg.matches(".*\"[^\"]+\".*"));

        // All lines must use → arrow separator
        assertThat(logMessages)
                .as("All [C1] lines must use → arrow separator")
                .filteredOn(msg -> msg.startsWith("[C1]"))
                .allMatch(msg -> msg.contains(" → "));

        assertThat(logMessages)
                .as("All [C1] lines should contain ✅ when strategy triggers")
                .filteredOn(msg -> msg.startsWith("[C1]"))
                .allMatch(msg -> msg.contains("✅"));

        // Exactly 6 C1 log lines — one per condition
        assertThat(logMessages)
                .as("Exactly 6 [C1] log lines expected (one per condition)")
                .filteredOn(msg -> msg.startsWith("[C1]"))
                .hasSize(6);
    }
}
