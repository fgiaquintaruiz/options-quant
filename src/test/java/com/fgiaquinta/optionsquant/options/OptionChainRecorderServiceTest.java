package com.fgiaquinta.optionsquant.options;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests OptionChainRecorderService using a mock OptionChainIbkrGateway.
 * IBKR connection complexity is hidden behind the gateway interface; these tests
 * exercise the orchestration logic only (ATM selection, expiry selection, persistence).
 */
class OptionChainRecorderServiceTest {

    private OptionChainSnapshotRepository repository;
    private OptionChainIbkrGateway ibkrGateway;
    private OptionChainRecorderService service;

    @BeforeEach
    void setUp() {
        repository = mock(OptionChainSnapshotRepository.class);
        ibkrGateway = mock(OptionChainIbkrGateway.class);
        service = new OptionChainRecorderService(repository, ibkrGateway);
    }

    // ─── snapshotSync — strike selection ─────────────────────────────────────

    @Test
    @DisplayName("snapshotSync requests 11 strikes (ATM ± 5) from the gateway")
    void snapshotSync_requests11Strikes() {
        // Underlying at 200.0, valid strikes: 170 to 230 in steps of 5
        double underlyingPrice = 200.0;
        List<Double> validStrikes = buildStrikeRange(170.0, 230.0, 5.0);
        String expiry = "20260620";

        when(ibkrGateway.fetchOptionSnapshot(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(buildSnapshotResult(200.0));

        service.snapshotSync("NVDA", "sig-001", "p1", "PUT", "SIGNAL",
                underlyingPrice, validStrikes, expiry);

        // 11 strikes were requested from the gateway (direction=PUT → right="P" only)
        verify(ibkrGateway, times(11))
                .fetchOptionSnapshot(eq("NVDA"), anyDouble(), eq(expiry), anyString());
    }

    @Test
    @DisplayName("snapshotSync selects correct ATM strike from valid strikes")
    void snapshotSync_selectsAtmStrike() {
        // Underlying at 197.3 → closest strike is 195.0 (if strikes are 180,185,190,195,200,205...)
        double underlyingPrice = 197.3;
        List<Double> validStrikes = buildStrikeRange(175.0, 225.0, 5.0);

        when(ibkrGateway.fetchOptionSnapshot(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(buildSnapshotResult(197.3));

        service.snapshotSync("AAPL", "sig-002", null, "CALL", "SCHEDULED",
                underlyingPrice, validStrikes, "20260620");

        // ATM = 195.0 (closest to 197.3), strikes selected: 170,175,180,185,190,195,200,205,210,215,220
        // Verify one of the expected ATM ± boundary calls
        verify(ibkrGateway, atLeastOnce())
                .fetchOptionSnapshot(eq("AAPL"), eq(195.0), anyString(), anyString());
    }

    // ─── snapshotSync — persistence ──────────────────────────────────────────

    @Test
    @DisplayName("snapshotSync persists one row per strike after gateway responses")
    void snapshotSync_persistsRowsAfterGatewayResponds() {
        double underlyingPrice = 500.0;
        List<Double> validStrikes = buildStrikeRange(460.0, 540.0, 5.0);
        String expiry = "20260620";

        OptionChainIbkrGateway.OptionSnapshot snapshot = buildSnapshotResult(500.0);
        when(ibkrGateway.fetchOptionSnapshot(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(snapshot);

        service.snapshotSync("SPY", "sig-003", null, "CALL", "SCHEDULED",
                underlyingPrice, validStrikes, expiry);

        // One row saved per strike (11 total)
        verify(repository, times(11)).save(any(OptionChainSnapshotRow.class));
    }

    @Test
    @DisplayName("snapshotSync maps gateway result into OptionChainSnapshotRow correctly")
    void snapshotSync_mapsRowFieldsCorrectly() {
        double underlyingPrice = 300.0;
        List<Double> validStrikes = buildStrikeRange(265.0, 335.0, 5.0);

        OptionChainIbkrGateway.OptionSnapshot snap = new OptionChainIbkrGateway.OptionSnapshot(
                2.10, 2.15, 300.0,
                0.38, -0.42, 0.003, -0.09, 0.28, 2.12
        );
        when(ibkrGateway.fetchOptionSnapshot(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(snap);

        service.snapshotSync("TSLA", "sig-map", "c1", "CALL", "SIGNAL",
                underlyingPrice, validStrikes, "20260620");

        // Capture one of the saved rows and verify its fields
        ArgumentCaptor<OptionChainSnapshotRow> captor = ArgumentCaptor.forClass(OptionChainSnapshotRow.class);
        verify(repository, times(11)).save(captor.capture());

        // Find the ATM row (strike closest to 300.0)
        OptionChainSnapshotRow atmRow = captor.getAllValues().stream()
                .filter(r -> r.strike() == 300.0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ATM row not found"));

        assertThat(atmRow.signalId()).isEqualTo("sig-map");
        assertThat(atmRow.ticker()).isEqualTo("TSLA");
        assertThat(atmRow.strategy()).isEqualTo("c1");
        assertThat(atmRow.direction()).isEqualTo("CALL");
        assertThat(atmRow.trigger()).isEqualTo("SIGNAL");
        assertThat(atmRow.bid()).isEqualTo(2.10);
        assertThat(atmRow.ask()).isEqualTo(2.15);
        assertThat(atmRow.iv()).isEqualTo(0.38);
        assertThat(atmRow.delta()).isEqualTo(-0.42);
    }

    @Test
    @DisplayName("snapshotSync still persists partial results when gateway returns null for some strikes")
    void snapshotSync_partialNullResults_stillPersistsNonNullStrikes() {
        double underlyingPrice = 100.0;
        List<Double> validStrikes = buildStrikeRange(70.0, 130.0, 5.0);

        // Some strikes return null (timeout/no data), others return data
        when(ibkrGateway.fetchOptionSnapshot(anyString(), anyDouble(), anyString(), anyString()))
                .thenReturn(null)  // first call returns null
                .thenReturn(buildSnapshotResult(100.0));  // subsequent calls return data

        service.snapshotSync("AMD", "sig-partial", null, "PUT", "SCHEDULED",
                underlyingPrice, validStrikes, "20260620");

        // Only 10 non-null responses should be persisted (first strike was null)
        verify(repository, times(10)).save(any(OptionChainSnapshotRow.class));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static List<Double> buildStrikeRange(double from, double to, double step) {
        List<Double> strikes = new java.util.ArrayList<>();
        for (double s = from; s <= to + 0.001; s += step) {
            strikes.add(Math.round(s * 100.0) / 100.0);
        }
        return strikes;
    }

    private static OptionChainIbkrGateway.OptionSnapshot buildSnapshotResult(double underlying) {
        return new OptionChainIbkrGateway.OptionSnapshot(
                1.50, 1.55, underlying,
                0.35, -0.40, 0.002, -0.07, 0.22, 1.52
        );
    }
}
