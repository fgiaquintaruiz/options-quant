package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every TradingStrategy declares the timeframes it actually uses
 * via the TimeframeRequirements interface. The required-timeframes table is the
 * source of truth that StrategyScannerService uses to skip strategies whose
 * data is missing — instead of skipping the entire ticker.
 */
class TimeframeRequirementsTest {

    static Stream<Arguments> strategyToRequiredTimeframes() {
        return Stream.of(
                // Squeeze (C1/P1) → 15m + 1h
                Arguments.of(new C1SqueezeCallStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1)),
                Arguments.of(new P1SqueezePutStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1)),

                // Trend (C2/P2) → 15m + 1h + 1d
                Arguments.of(new C2TrendCallStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)),
                Arguments.of(new P2TrendPutStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)),

                // Bounce (C3/P3) → 15m + 1h + 1d
                Arguments.of(new C3BounceCallStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)),
                Arguments.of(new P3BouncePutStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)),

                // Opening (C4/P4) → 5m + 15m  (only strategies needing MIN_5)
                Arguments.of(new C4OpeningCallStrategy(),
                        Set.of(TimeFrame.MIN_5, TimeFrame.MIN_15)),
                Arguments.of(new P4OpeningPutStrategy(),
                        Set.of(TimeFrame.MIN_5, TimeFrame.MIN_15)),

                // Continuation (C5/P5) → 15m + 1h + 1d
                Arguments.of(new C5ContinuationCallStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)),
                Arguments.of(new P5ContinuationPutStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)),

                // Reversal (C6/P6) → 15m + 1h + 1d (verified via source: needs DAY_1 too)
                Arguments.of(new C6ReversalCallStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1)),
                Arguments.of(new P6ReversalPutStrategy(),
                        Set.of(TimeFrame.MIN_15, TimeFrame.HOUR_1, TimeFrame.DAY_1))
        );
    }

    @ParameterizedTest(name = "{0} requires {1}")
    @MethodSource("strategyToRequiredTimeframes")
    @DisplayName("Each strategy declares the exact timeframes it uses")
    void strategy_declaresExpectedRequiredTimeframes(TradingStrategy strategy,
                                                     Set<TimeFrame> expected) {
        assertThat(strategy).isInstanceOf(TimeframeRequirements.class);

        Set<TimeFrame> actual = ((TimeframeRequirements) strategy).requiredTimeframes();

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("Only C4 and P4 (Opening) require MIN_5 — all others must NOT require it")
    void onlyOpeningStrategies_requireMin5() {
        Stream<TradingStrategy> nonOpeningStrategies = Stream.of(
                new C1SqueezeCallStrategy(), new P1SqueezePutStrategy(),
                new C2TrendCallStrategy(), new P2TrendPutStrategy(),
                new C3BounceCallStrategy(), new P3BouncePutStrategy(),
                new C5ContinuationCallStrategy(), new P5ContinuationPutStrategy(),
                new C6ReversalCallStrategy(), new P6ReversalPutStrategy()
        );

        nonOpeningStrategies.forEach(s -> {
            Set<TimeFrame> required = ((TimeframeRequirements) s).requiredTimeframes();
            assertThat(required)
                    .as("%s must NOT require MIN_5", s.getClass().getSimpleName())
                    .doesNotContain(TimeFrame.MIN_5);
        });

        // Opening strategies DO require it
        assertThat(((TimeframeRequirements) new C4OpeningCallStrategy()).requiredTimeframes())
                .contains(TimeFrame.MIN_5);
        assertThat(((TimeframeRequirements) new P4OpeningPutStrategy()).requiredTimeframes())
                .contains(TimeFrame.MIN_5);
    }
}
