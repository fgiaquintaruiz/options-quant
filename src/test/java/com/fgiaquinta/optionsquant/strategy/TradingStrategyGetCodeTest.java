package com.fgiaquinta.optionsquant.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — RED/GREEN tests for TradingStrategy.getCode().
 *
 * <p>getCode() must extract the short code (c1-c6, p1-p6) from the class simple name.
 * All 12 concrete strategy classes are covered.
 */
class TradingStrategyGetCodeTest {

    @Test
    void getCode_c1SqueezeCallStrategy_returnsC1() {
        assertThat(new C1SqueezeCallStrategy().getCode()).isEqualTo("c1");
    }

    @Test
    void getCode_c2TrendCallStrategy_returnsC2() {
        assertThat(new C2TrendCallStrategy().getCode()).isEqualTo("c2");
    }

    @Test
    void getCode_c3BounceCallStrategy_returnsC3() {
        assertThat(new C3BounceCallStrategy().getCode()).isEqualTo("c3");
    }

    @Test
    void getCode_c4OpeningCallStrategy_returnsC4() {
        assertThat(new C4OpeningCallStrategy().getCode()).isEqualTo("c4");
    }

    @Test
    void getCode_c5ContinuationCallStrategy_returnsC5() {
        assertThat(new C5ContinuationCallStrategy().getCode()).isEqualTo("c5");
    }

    @Test
    void getCode_c6ReversalCallStrategy_returnsC6() {
        assertThat(new C6ReversalCallStrategy().getCode()).isEqualTo("c6");
    }

    @Test
    void getCode_p1SqueezePutStrategy_returnsP1() {
        assertThat(new P1SqueezePutStrategy().getCode()).isEqualTo("p1");
    }

    @Test
    void getCode_p2TrendPutStrategy_returnsP2() {
        assertThat(new P2TrendPutStrategy().getCode()).isEqualTo("p2");
    }

    @Test
    void getCode_p3BouncePutStrategy_returnsP3() {
        assertThat(new P3BouncePutStrategy().getCode()).isEqualTo("p3");
    }

    @Test
    void getCode_p4OpeningPutStrategy_returnsP4() {
        assertThat(new P4OpeningPutStrategy().getCode()).isEqualTo("p4");
    }

    @Test
    void getCode_p5ContinuationPutStrategy_returnsP5() {
        assertThat(new P5ContinuationPutStrategy().getCode()).isEqualTo("p5");
    }

    @Test
    void getCode_p6ReversalPutStrategy_returnsP6() {
        assertThat(new P6ReversalPutStrategy().getCode()).isEqualTo("p6");
    }
}
