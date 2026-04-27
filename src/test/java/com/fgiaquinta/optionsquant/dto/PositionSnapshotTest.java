package com.fgiaquinta.optionsquant.dto;

import com.ib.client.Contract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED: Verifies PositionSnapshot record fields are immutable and accessible.
 * All tests fail until PositionSnapshot is created (GREEN).
 */
class PositionSnapshotTest {

    @Test
    @DisplayName("positionSnapshot_withAllFields_accessorsReturnCorrectValues")
    void positionSnapshot_withAllFields_accessorsReturnCorrectValues() {
        // GIVEN
        Contract contract = new Contract();
        contract.symbol("NVDA");
        contract.secType("STK");
        Instant now = Instant.parse("2026-04-26T14:30:00Z");

        // WHEN
        PositionSnapshot snapshot = new PositionSnapshot("NVDA", "STK", contract, 100, 87.50, now);

        // THEN — every accessor returns the value supplied at construction
        assertThat(snapshot.symbol()).isEqualTo("NVDA");
        assertThat(snapshot.secType()).isEqualTo("STK");
        assertThat(snapshot.contract()).isSameAs(contract);
        assertThat(snapshot.quantity()).isEqualTo(100);
        assertThat(snapshot.avgCost()).isEqualTo(87.50);
        assertThat(snapshot.snapshotAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("positionSnapshot_isImmutable_equalityByValue")
    void positionSnapshot_isImmutable_equalityByValue() {
        // GIVEN — same values, two distinct instances
        Contract c1 = new Contract();
        c1.symbol("AAPL");
        Contract c2 = new Contract();
        c2.symbol("AAPL");
        Instant ts = Instant.parse("2026-04-26T15:00:00Z");

        PositionSnapshot s1 = new PositionSnapshot("AAPL", "STK", c1, 50, 150.0, ts);
        PositionSnapshot s2 = new PositionSnapshot("AAPL", "STK", c1, 50, 150.0, ts);

        // THEN — records use structural equality
        assertThat(s1).isEqualTo(s2);
        assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
    }

    @Test
    @DisplayName("positionSnapshot_optType_isRepresentable")
    void positionSnapshot_optType_isRepresentable() {
        // GIVEN — option secType
        Contract optContract = new Contract();
        optContract.symbol("SPY");
        optContract.secType("OPT");
        Instant ts = Instant.now();

        // WHEN
        PositionSnapshot snapshot = new PositionSnapshot("SPY", "OPT", optContract, 10, 5.50, ts);

        // THEN
        assertThat(snapshot.secType()).isEqualTo("OPT");
        assertThat(snapshot.quantity()).isEqualTo(10);
        assertThat(snapshot.avgCost()).isEqualTo(5.50);
    }
}
