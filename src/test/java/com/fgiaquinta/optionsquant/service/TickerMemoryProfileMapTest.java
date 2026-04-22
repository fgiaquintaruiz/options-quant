package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TickerMemoryProfileMapTest {

    @Test
    @DisplayName("loadStrategyProfileMap round-trips sl/tp override fields from JSON-like map")
    void roundTripRiskOverrides() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", "AAPL");
        m.put("strategy", "c1 squeeze call");
        m.put("slAtrMultOverride", 2.1);
        m.put("tpAtrMultOverride", 3.7);

        TickerStrategyProfile p = TickerMemory.loadStrategyProfileMap(m);
        assertThat(p.ticker).isEqualTo("AAPL");
        assertThat(p.strategy).isEqualTo("c1 squeeze call");
        assertThat(p.getSlAtrMultOverride()).isEqualTo(2.1);
        assertThat(p.getTpAtrMultOverride()).isEqualTo(3.7);
    }

    @Test
    @DisplayName("Missing override keys leave null — RiskCalculator uses global strategy maps")
    void missingOverridesDefaultToNull() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", "MSFT");
        m.put("strategy", "p5 continuation");
        m.put("totalTrades", 0);

        TickerStrategyProfile p = TickerMemory.loadStrategyProfileMap(m);
        assertThat(p.getSlAtrMultOverride()).isNull();
        assertThat(p.getTpAtrMultOverride()).isNull();
    }

    @Test
    @DisplayName("Unknown keys in map are ignored (forward-compatible)")
    void ignoresUnknownKeys() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", "X");
        m.put("strategy", "y");
        m.put("futureRiskFieldWeDoNotKnowYet", 42);

        TickerStrategyProfile p = TickerMemory.loadStrategyProfileMap(m);
        assertThat(p.ticker).isEqualTo("X");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "p5 continuation, p5 continuation",
            "P5 Continuation, p5 continuation",
            "p5continuation, p5 continuation",
            "p4openingput, p4 opening",
            "c2trendcall, c2 trend",
    })
    @DisplayName("canonicalStrategyName matches TradingStrategy#getName style")
    void canonicalStrategyNameMatchesEngine(String raw, String expected) {
        assertThat(TickerMemory.canonicalStrategyName(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("profileMapKey is stable for ticker case and strategy aliases")
    void profileMapKeyStable() {
        assertThat(TickerMemory.profileMapKey("spy", "p5 continuation"))
                .isEqualTo(TickerMemory.profileMapKey("SPY", "p5continuation"));
    }
}
