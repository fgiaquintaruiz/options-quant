package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import com.fgiaquinta.optionsquant.service.AccountManager;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * T27 — Spring context validation test.
 *
 * Loads the full application context and verifies it starts without errors.
 * No assertions needed — the test passes if {@code @SpringBootTest} completes startup.
 *
 * Mocks all beans that open real sockets (TWS) to keep the test self-contained.
 * Uses {@code candles.store=sqlite} with {@code :memory:} path so SQLite beans
 * initialise without touching the filesystem.
 */
@SpringBootTest(
    classes = OptionsQuantApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "candles.store=sqlite",
    "candles.sqlite.path=:memory:"
})
class SpringContextValidationTest {

    // Mock all beans that would open external connections at startup
    @MockitoBean
    private IbkrService ibkrService;

    @MockitoBean
    private AccountManager accountManager;

    @MockitoBean
    private OrderExecutionService orderExecutionService;

    @MockitoBean
    private MetricsService metricsService;

    @Test
    void springContext_loadsWithoutError() {
        // If the context starts without throwing, the test passes.
        // This catches any remaining bean wiring issues introduced by this SDD.
    }
}
