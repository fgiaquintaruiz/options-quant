package com.fgiaquinta.optionsquant.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IbkrPropertiesTest {

    private static IbkrProperties propsWithAccount(String accountId) {
        return new IbkrProperties(
            "localhost", 7497, 10, List.of("SPY"), true, accountId, 1, 0.02, false, List.of("SPY"), 20,
            List.of(),
            1, 2, 999
        );
    }

    @Test
    void shouldHaveDescriptiveClientIdAccessors() {
        IbkrProperties props = propsWithAccount("DU123");

        assertEquals(1, props.historicalDataClientId());
        assertEquals(2, props.orderExecutionClientId());
        assertEquals(999, props.accountManagerClientId());
    }

    @Test
    void isPaperAccount_returnsTrueForDuPrefix() {
        assertTrue(propsWithAccount("DUN598126").isPaperAccount());
        assertTrue(propsWithAccount("DU123").isPaperAccount());
    }

    @Test
    void isPaperAccount_returnsFalseForNonDuPrefix() {
        assertFalse(propsWithAccount("U1234567").isPaperAccount());
        assertFalse(propsWithAccount("F1234567").isPaperAccount());
    }

    @Test
    void isPaperAccount_returnsFalseForNullOrBlankAccount() {
        assertFalse(propsWithAccount(null).isPaperAccount());
        assertFalse(propsWithAccount("").isPaperAccount());
        assertFalse(propsWithAccount("   ").isPaperAccount());
    }
}
