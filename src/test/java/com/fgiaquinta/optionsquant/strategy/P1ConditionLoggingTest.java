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
 * Verifies that P1SqueezePutStrategy emits per-condition DEBUG log lines in the format:
 *   [P1] <ticker> @ <time> — <description with value>: ✅  (or ❌ STOP)
 *
 * The logger name is P1SqueezePutStrategy (Lombok @Slf4j convention).
 */
class P1ConditionLoggingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private ListAppender<ILoggingEvent> logAppender;
    private Logger p1Logger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        p1Logger = (Logger) LoggerFactory.getLogger(P1SqueezePutStrategy.class);
        logAppender.start();
        p1Logger.addAppender(logAppender);
        p1Logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        if (p1Logger != null && logAppender != null) {
            p1Logger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // =========================================================================
    // Shared data builders (reused from C1SqueezeBreakoutBufferTest pattern)
    // =========================================================================

    private Candle candle(ZonedDateTime time, double open, double high, double low, double close, long volume) {
        return new Candle(time, open, high, low, close, volume);
    }

    /**
     * Builds StrategyData that causes P1 to FAIL at the SMA-spread condition.
     * The hourly closes form a strong downtrend (SMAs are NOT compressed).
     */
    private StrategyData buildDataThatFailsSmaSpread(ZonedDateTime currentTime) {
        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        // Strong downtrend: prices drop from 200 to 80 over 280 bars — SMAs will spread far apart
        for (int i = 0; i < totalBars - 1; i++) {
            double price = 200.0 - (i * 0.43); // 200 → ~80 over 279 bars
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    price, price + 0.5, price - 0.5, price,
                    1000000L
            ));
        }
        // Last bar: red candle below imaginary floor
        double lastPrice = 80.0;
        hourlyCandles.add(candle(
                currentTime,
                lastPrice + 1.0,
                lastPrice + 1.5,
                lastPrice - 0.5,
                lastPrice,
                2000000L
        ));

        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(20L * 15);
        for (int i = 0; i < 20; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 100, 101, 99, 100, 500000L));
        }
        candles15m.add(candle(currentTime, 81.0, 81.5, 80.5, 80.5, 2000000L));

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourlyCandles);
        data.put(TimeFrame.MIN_15, candles15m);
        return new StrategyData(data);
    }

    /**
     * Builds StrategyData where P1 fires fully (all conditions pass).
     */
    private StrategyData buildDataThatTriggers(ZonedDateTime currentTime) {
        double floor = 100.0;
        double breakoutClose = floor * 0.996; // 0.4% below floor — clears buffer
        double breakoutOpen  = floor + 1.0;

        int totalBars = 280;
        List<Candle> hourlyCandles = new ArrayList<>();
        ZonedDateTime hourBase = currentTime.minusHours(totalBars);

        for (int i = 0; i < totalBars - 1; i++) {
            double base = floor + 1.0 + (i % 3) * 0.2 - 0.2;
            hourlyCandles.add(candle(
                    hourBase.plusHours(i),
                    base, base + 0.5, floor, base,
                    2000000L
            ));
        }
        hourlyCandles.add(candle(
                currentTime,
                breakoutOpen, breakoutOpen + 0.1, breakoutClose - 0.1, breakoutClose,
                2000000L
        ));

        // 15m candles: 20 lateral + 1 riding lower BB
        List<Candle> candles15m = new ArrayList<>();
        ZonedDateTime m15Base = currentTime.minusMinutes(20L * 15);
        for (int i = 0; i < 20; i++) {
            candles15m.add(candle(m15Base.plusMinutes(15L * i), 100, 100.5, 99.5, 100, 500000L));
        }
        candles15m.add(candle(
                currentTime,
                breakoutOpen, breakoutOpen + 0.1, breakoutClose - 0.1, breakoutClose,
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
    @DisplayName("When SMA spread condition fails, log contains ❌ line for that condition")
    void whenSmaSpreadFails_logContainsFailureLineWithActualValue() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatFailsSmaSpread(testTime);
        P1SqueezePutStrategy strategy = new P1SqueezePutStrategy();

        boolean triggered = strategy.isTriggered("TEST", data, testTime);

        assertThat(triggered).isFalse();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        // Must contain at least one line with [P1] prefix
        assertThat(logMessages)
                .as("Expected at least one [P1] DEBUG log line")
                .anyMatch(msg -> msg.startsWith("[P1]"));

        // The failing condition must include ❌
        assertThat(logMessages)
                .as("Expected a ❌ log line for the failed condition")
                .anyMatch(msg -> msg.contains("❌"));

        // The description must contain an actual numeric value (not just a label)
        assertThat(logMessages)
                .as("Condition description must include the computed spread value (e.g. 'spread 0.12 > 0.04')")
                .anyMatch(msg -> msg.contains("❌") && msg.matches(".*\\d+\\.\\d+.*"));
    }

    @Test
    @DisplayName("When all conditions pass, every log line contains ✅ and none contain ❌")
    void whenAllConditionsPass_allLogLinesShowCheckmark() {
        ZonedDateTime testTime = ZonedDateTime.of(2026, 4, 8, 14, 0, 0, 0, NY);
        StrategyData data = buildDataThatTriggers(testTime);
        P1SqueezePutStrategy strategy = new P1SqueezePutStrategy(0.003);

        boolean triggered = strategy.isTriggered("TEST", data, testTime);

        assertThat(triggered).isTrue();

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(logMessages)
                .as("Expected at least one [P1] DEBUG log line when strategy triggers")
                .anyMatch(msg -> msg.startsWith("[P1]"));

        assertThat(logMessages)
                .as("No ❌ lines should appear when strategy triggers successfully")
                .noneMatch(msg -> msg.startsWith("[P1]") && msg.contains("❌"));

        assertThat(logMessages)
                .as("All [P1] lines should contain ✅ when strategy triggers")
                .filteredOn(msg -> msg.startsWith("[P1]"))
                .allMatch(msg -> msg.contains("✅"));
    }
}
