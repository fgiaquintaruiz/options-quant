package com.fgiaquinta.optionsquant.config;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IbkrPropertiesTest {

    @Test
    void shouldHaveDescriptiveClientIdAccessors() {
        IbkrProperties props = new IbkrProperties(
            "localhost", 7497, 10, List.of("SPY"), true, "DU123", 1, 0.02, false, List.of("SPY"), 20,
            1, 2, 999
        );

        // These should fail to compile initially because the methods don't exist
        assertEquals(1, props.historicalDataClientId());
        assertEquals(2, props.orderExecutionClientId());
        assertEquals(999, props.accountManagerClientId());
    }
}
