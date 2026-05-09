package com.fgiaquinta.optionsquant.strategy.data;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the new {@link StrategyData#hasAvailableTimeframes(Set)} method,
 * used by {@code StrategyScannerService} to skip individual strategies whose
 * required timeframes aren't loaded — instead of skipping the whole ticker.
 */
class StrategyDataAvailabilityTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private List<Candle> sampleCandles() {
        ZonedDateTime base = ZonedDateTime.of(2026, 4, 6, 9, 30, 0, 0, NY);
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            candles.add(new Candle(base.plusMinutes(15L * i), 100, 101, 99, 100, 1000L));
        }
        return candles;
    }

    @Test
    @DisplayName("hasAvailableTimeframes returns true when all required timeframes are present")
    void returnsTrue_whenAllRequiredTimeframesPresent() {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.MIN_15, sampleCandles());
        data.put(TimeFrame.HOUR_1, sampleCandles());
        data.put(TimeFrame.DAY_1, sampleCandles());
        StrategyData strategyData = new StrategyData(data);

        assertThat(strategyData.hasAvailableTimeframes(
                Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)))
                .isTrue();
    }

    @Test
    @DisplayName("hasAvailableTimeframes returns false when one required timeframe is missing")
    void returnsFalse_whenOneRequiredTimeframeMissing() {
        // MIN_5 not loaded → opening strategies should NOT be runnable
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.MIN_15, sampleCandles());
        data.put(TimeFrame.HOUR_1, sampleCandles());
        data.put(TimeFrame.DAY_1, sampleCandles());
        StrategyData strategyData = new StrategyData(data);

        assertThat(strategyData.hasAvailableTimeframes(
                Set.of(TimeFrame.MIN_5, TimeFrame.MIN_15)))
                .isFalse();
    }

    @Test
    @DisplayName("hasAvailableTimeframes returns false when timeframe is present but empty")
    void returnsFalse_whenRequiredTimeframeIsEmpty() {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.MIN_15, sampleCandles());
        data.put(TimeFrame.HOUR_1, new ArrayList<>()); // empty
        StrategyData strategyData = new StrategyData(data);

        assertThat(strategyData.hasAvailableTimeframes(
                Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1)))
                .isFalse();
    }

    @Test
    @DisplayName("hasAvailableTimeframes returns true for empty required set (no requirements)")
    void returnsTrue_whenRequiredSetIsEmpty() {
        StrategyData strategyData = new StrategyData(new EnumMap<>(TimeFrame.class));

        assertThat(strategyData.hasAvailableTimeframes(Set.of())).isTrue();
    }

    @Test
    @DisplayName("Squeeze-style requirements (MIN_15+HOUR_1) pass even when MIN_5 and DAY_1 missing")
    void squeezeRequirements_passWithOnlyTwoTimeframes() {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.MIN_15, sampleCandles());
        data.put(TimeFrame.HOUR_1, sampleCandles());
        StrategyData strategyData = new StrategyData(data);

        assertThat(strategyData.hasAvailableTimeframes(
                Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1)))
                .isTrue();

        // But it should NOT pass for trend (which also needs DAY_1)
        assertThat(strategyData.hasAvailableTimeframes(
                Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)))
                .isFalse();
    }
}
