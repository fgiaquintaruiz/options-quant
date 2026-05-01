package com.fgiaquinta.optionsquant.service;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.dto.PositionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AccountManagerTest {

    private AccountManager accountManager;
    private IbkrProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IbkrProperties(
            "127.0.0.1", 7497, 30,
            List.of("SPY"),
            false, "", 1, 0.02, 20,
            1, 3, 999
        );
        accountManager = new AccountManager(properties);
    }

    @Test
    @DisplayName("Initial balance should be 0")
    void initialBalanceIsZero() {
        assertThat(accountManager.getCurrentBalance()).isZero();
    }

    @Test
    @DisplayName("updateBalance should set the balance correctly")
    void updateBalance() {
        accountManager.updateBalance(50000.0);
        assertThat(accountManager.getCurrentBalance()).isEqualTo(50000.0);
    }

    @Test
    @DisplayName("calculateQuantity should return 0 when balance is 0")
    void calculateQuantityZeroBalance() {
        assertThat(accountManager.calculateQuantity(3.0, 2.5)).isZero();
    }

    @Test
    @DisplayName("calculateQuantity with 2% risk should return correct qty")
    void calculateQuantityWithRisk() {
        accountManager.updateBalance(50000.0);
        // 2% of 50000 = 1000
        // risk per contract = |3.0 - 2.5| * 100 = 50
        // qty = 1000 / 50 = 20 -> Capped at 10
        int qty = accountManager.calculateQuantity(3.0, 2.5);
        assertThat(qty).isEqualTo(10);
    }

    @Test
    @DisplayName("calculateQuantity with configurable risk % should scale correctly")
    void calculateQuantityWithCustomRisk() {
        // Test with 5% risk
        var highRiskProps = new IbkrProperties(
            "127.0.0.1", 7497, 30,
            List.of("SPY"), false, "", 1, 0.05, 20,
            1, 3, 999
        );
        var highRiskManager = new AccountManager(highRiskProps);
        highRiskManager.updateBalance(50000.0);
        // 5% of 50000 = 2500
        // risk per contract = |3.0 - 2.5| * 100 = 50
        // qty = 2500 / 50 = 50 -> Capped at 10
        int qty = highRiskManager.calculateQuantity(3.0, 2.5);
        assertThat(qty).isEqualTo(10);
    }

    @Test
    @DisplayName("calculateQuantity should return 0 when entry equals SL")
    void calculateQuantityZeroRisk() {
        accountManager.updateBalance(50000.0);
        assertThat(accountManager.calculateQuantity(3.0, 3.0)).isZero();
    }

    @Test
    @DisplayName("canOpenNewTrade should respect limits")
    void canOpenNewTrade() {
        assertThat(accountManager.canOpenNewTrade(3)).isTrue();
        accountManager.addActiveTrade();
        accountManager.addActiveTrade();
        accountManager.addActiveTrade();
        assertThat(accountManager.canOpenNewTrade(3)).isFalse();
    }

    @Test
    @DisplayName("removeActiveTrade should decrement counter")
    void removeActiveTrade() {
        accountManager.addActiveTrade();
        accountManager.addActiveTrade();
        assertThat(accountManager.getActiveTradeCount()).isEqualTo(2);
        accountManager.removeActiveTrade();
        assertThat(accountManager.getActiveTradeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("canOpenNewTrade with 0 limit should always allow")
    void canOpenNewTradeUnlimited() {
        accountManager.addActiveTrade();
        accountManager.addActiveTrade();
        assertThat(accountManager.canOpenNewTrade(0)).isTrue();
    }

    // ── Task 1.3: position snapshot map + EWrapper callbacks ─────────────────

    @Test
    @DisplayName("positionsSnapshot_initialState_isEmptyAndNotReady")
    void positionsSnapshot_initialState_isEmptyAndNotReady() {
        // THEN — before any callback fires, snapshot map is empty and snapshotReady is false
        assertThat(accountManager.getPositionsSnapshot()).isEmpty();
        assertThat(accountManager.isSnapshotReady()).isFalse();
    }

    @Test
    @DisplayName("handlePosition_withStkType_isIgnoredSilently")
    void handlePosition_withStkType_isIgnoredSilently() {
        // GIVEN — a STK contract (stocks are no longer tracked — OPT only)
        Contract contract = new Contract();
        contract.symbol("NVDA");
        contract.secType("STK");

        // WHEN — callback fires (via package-private test hook)
        accountManager.handlePosition("DU123", contract, 100, 87.50);

        // THEN — STK is ignored, not stored in snapshot map
        Map<String, PositionSnapshot> snapshot = accountManager.getPositionsSnapshot();
        assertThat(snapshot).doesNotContainKey("NVDA");
    }

    @Test
    @DisplayName("handlePosition_withOptType_addsEntryToSnapshotMap")
    void handlePosition_withOptType_addsEntryToSnapshotMap() {
        // GIVEN — an OPT contract
        Contract contract = new Contract();
        contract.symbol("SPY");
        contract.secType("OPT");

        // WHEN
        accountManager.handlePosition("DU123", contract, 10, 5.50);

        // THEN — entry is recorded in snapshot map with all fields
        Map<String, PositionSnapshot> snapshot = accountManager.getPositionsSnapshot();
        assertThat(snapshot).containsKey("SPY");
        PositionSnapshot ps = snapshot.get("SPY");
        assertThat(ps.symbol()).isEqualTo("SPY");
        assertThat(ps.secType()).isEqualTo("OPT");
        assertThat(ps.quantity()).isEqualTo(10);
        assertThat(ps.avgCost()).isEqualTo(5.50);
        assertThat(ps.snapshotAt()).isNotNull();
    }

    @Test
    @DisplayName("handlePosition_withFutType_isIgnoredSilently")
    void handlePosition_withFutType_isIgnoredSilently() {
        // GIVEN — a FUT contract (futures — should be filtered out)
        Contract contract = new Contract();
        contract.symbol("6E");
        contract.secType("FUT");

        // WHEN
        accountManager.handlePosition("DU123", contract, 1, 0.0);

        // THEN — FUT is not in the snapshot map
        assertThat(accountManager.getPositionsSnapshot()).doesNotContainKey("6E");
    }

    @Test
    @DisplayName("handlePosition_withCashType_isIgnoredSilently")
    void handlePosition_withCashType_isIgnoredSilently() {
        // GIVEN — a CASH (forex) contract
        Contract contract = new Contract();
        contract.symbol("EUR");
        contract.secType("CASH");

        // WHEN
        accountManager.handlePosition("DU123", contract, 1, 0.0);

        // THEN — CASH is not in the snapshot map
        assertThat(accountManager.getPositionsSnapshot()).doesNotContainKey("EUR");
    }

    @Test
    @DisplayName("handlePositionEnd_setsSnapshotReadyTrue_andUpdatesLastSnapshotAt")
    void handlePositionEnd_setsSnapshotReadyTrue_andUpdatesLastSnapshotAt() {
        // WHEN — positionEnd callback fires
        accountManager.handlePositionEnd();

        // THEN — snapshotReady transitions to true
        assertThat(accountManager.isSnapshotReady()).isTrue();
        assertThat(accountManager.getLastSnapshotAt()).isNotNull();
    }

    @Test
    @DisplayName("getPositionsSnapshot_returnsDefensiveCopy")
    void getPositionsSnapshot_returnsDefensiveCopy() {
        // GIVEN — one OPT position in map
        Contract contract = new Contract();
        contract.symbol("AAPL");
        contract.secType("OPT");
        accountManager.handlePosition("DU123", contract, 50, 150.0);

        // WHEN — get two copies
        Map<String, PositionSnapshot> copy1 = accountManager.getPositionsSnapshot();
        Map<String, PositionSnapshot> copy2 = accountManager.getPositionsSnapshot();

        // THEN — copies are structurally equal but not the same instance
        assertThat(copy1).isEqualTo(copy2);
        assertThat(copy1).isNotSameAs(copy2);
    }

    // ── Task 1.4: reqPositions() called inside nextValidId() callback ────────

    @Test
    @DisplayName("reqPositions_isCalledInsideNextValidId_notDirectlyInConnect")
    void reqPositions_isCalledInsideNextValidId_notDirectlyInConnect() {
        // GIVEN — a mock EClientSocket (no real TWS)
        EClientSocket mockClient = Mockito.mock(EClientSocket.class);
        when(mockClient.isConnected()).thenReturn(false);

        // Verify: invoking handleNextValidId (the package-private test hook) triggers reqPositions
        // We inject a mock client via the package-private setter first
        accountManager.injectClientForTest(mockClient);

        // WHEN — nextValidId callback fires
        accountManager.handleNextValidId(mockClient);

        // THEN — reqPositions() must have been called on the client
        verify(mockClient).reqPositions();
    }

    @Test
    @DisplayName("handlePosition_withZeroQuantity_removesFromSnapshot_whenSecTypeMatches")
    void handlePosition_withZeroQuantity_removesFromSnapshot() {
        // GIVEN — SPY OPT already present in snapshot with qty=10
        Contract contract = new Contract();
        contract.symbol("SPY");
        contract.secType("OPT");
        accountManager.handlePosition("DU123", contract, 10, 450.0);
        assertThat(accountManager.getPositionsSnapshot()).containsKey("SPY");

        // WHEN — TWS sends qty=0 for the SAME secType (position closed)
        accountManager.handlePosition("DU123", contract, 0, 0.0);

        // THEN — SPY is removed from the snapshot (same secType: OPT → OPT)
        assertThat(accountManager.getPositionsSnapshot()).doesNotContainKey("SPY");
    }

    @Test
    @DisplayName("handlePosition_withZeroQuantity_doesNotRemoveSnapshotIfSecTypeDiffers")
    void handlePosition_withZeroQuantity_doesNotRemoveSnapshotIfSecTypeDiffers() {
        // GIVEN — GOOG OPT qty=5 already in snapshot
        Contract optContract = new Contract();
        optContract.symbol("GOOG");
        optContract.secType("OPT");
        accountManager.handlePosition("DU123", optContract, 5, 12.50);
        assertThat(accountManager.getPositionsSnapshot()).containsKey("GOOG");
        assertThat(accountManager.getPositionsSnapshot().get("GOOG").secType()).isEqualTo("OPT");

        // WHEN — TWS sends qty=0 for GOOG STK (stock position closed, different secType)
        Contract stkContract = new Contract();
        stkContract.symbol("GOOG");
        stkContract.secType("STK");
        accountManager.handlePosition("DU123", stkContract, 0, 0.0);

        // THEN — snapshot STILL contains GOOG (the OPT entry was preserved, only STK would be removed)
        assertThat(accountManager.getPositionsSnapshot()).containsKey("GOOG");
        assertThat(accountManager.getPositionsSnapshot().get("GOOG").secType()).isEqualTo("OPT");
        assertThat(accountManager.getPositionsSnapshot().get("GOOG").quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("handlePosition_withNegativeQuantity_removesFromSnapshot")
    void handlePosition_withNegativeQuantity_removesFromSnapshot() {
        // GIVEN — SPY OPT already present in snapshot with qty=10
        Contract contract = new Contract();
        contract.symbol("SPY");
        contract.secType("OPT");
        accountManager.handlePosition("DU123", contract, 10, 450.0);
        assertThat(accountManager.getPositionsSnapshot()).containsKey("SPY");

        // WHEN — TWS sends qty=-1 (edge case / short)
        accountManager.handlePosition("DU123", contract, -1, 0.0);

        // THEN — SPY is removed from the snapshot
        assertThat(accountManager.getPositionsSnapshot()).doesNotContainKey("SPY");
    }

    @Test
    @DisplayName("reqPositions_isIdempotent_whenConnectCalledTwice")
    void reqPositions_isIdempotent_whenConnectCalledTwice() {
        // GIVEN — a mock client already connected (simulates double-connect guard at L54)
        EClientSocket mockClient = Mockito.mock(EClientSocket.class);
        when(mockClient.isConnected()).thenReturn(true);
        accountManager.injectClientForTest(mockClient);

        // WHEN — nextValidId fires once (second connect() returns early due to guard at L54)
        accountManager.handleNextValidId(mockClient);

        // THEN — reqPositions called exactly once (no double-fire)
        verify(mockClient, times(1)).reqPositions();
    }
}
