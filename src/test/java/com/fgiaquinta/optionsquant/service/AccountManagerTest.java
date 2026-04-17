package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountManagerTest {

    private AccountManager accountManager;
    private IbkrProperties properties;

    @BeforeEach
    void setUp() {
        properties = new IbkrProperties(
            "127.0.0.1", 7497, 30,
            List.of("SPY"),
            false, "", 1, 0.02, false, List.of("SPY", "QQQ", "AAPL"), 20
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
            List.of("SPY"), false, "", 1, 0.05, false, List.of("SPY", "QQQ", "AAPL"), 20
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
}
