package com.fgiaquinta.optionsquant.strategy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every TradingStrategy implementation correctly declares
 * its direction via isCall(). Call strategies return true; put strategies return false.
 * This replaces fragile string matching in BacktestEngine.
 */
class TradingStrategyIsCallTest {

    static Stream<Arguments> strategyIsCallTable() {
        return Stream.of(
                Arguments.of(new C1SqueezeCallStrategy(), true),
                Arguments.of(new C2TrendCallStrategy(), true),
                Arguments.of(new C3BounceCallStrategy(), true),
                Arguments.of(new C4OpeningCallStrategy(), true),
                Arguments.of(new C5ContinuationCallStrategy(), true),
                Arguments.of(new C6ReversalCallStrategy(), true),
                Arguments.of(new P1SqueezePutStrategy(), false),
                Arguments.of(new P2TrendPutStrategy(), false),
                Arguments.of(new P3BouncePutStrategy(), false),
                Arguments.of(new P4OpeningPutStrategy(), false),
                Arguments.of(new P5ContinuationPutStrategy(), false),
                Arguments.of(new P6ReversalPutStrategy(), false)
        );
    }

    @ParameterizedTest(name = "{0}.isCall() == {1}")
    @MethodSource("strategyIsCallTable")
    void strategy_returnsCorrectIsCall(TradingStrategy strategy, boolean expectedIsCall) {
        assertThat(strategy.isCall())
                .as("%s.isCall() should be %s", strategy.getClass().getSimpleName(), expectedIsCall)
                .isEqualTo(expectedIsCall);
    }
}
