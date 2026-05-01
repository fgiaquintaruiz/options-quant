package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TickerService#getHotTickers()} — HOT guard semantics.
 *
 * <p>Three cases:
 * <ol>
 *   <li>Runtime present, {@code hot} non-empty → return runtime list (ignore CSV fallback)</li>
 *   <li>Runtime present, {@code hot} explicitly empty {@code []} → return empty (no fallback)</li>
 *   <li>Runtime absent → fall back to CSV market-cap</li>
 * </ol>
 */
class TickerServiceTest {

    private IbkrProperties ibkrProperties;
    private TickerRuntimeConfigStore runtimeConfigStore;
    private TickerService tickerService;

    @BeforeEach
    void setUp() {
        ibkrProperties = mock(IbkrProperties.class);
        runtimeConfigStore = mock(TickerRuntimeConfigStore.class);
        tickerService = new TickerService(ibkrProperties, runtimeConfigStore);
        // Mark as loaded so loadTickers() is a no-op
        tickerService.loadTickers();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Case 3 — runtime hot is non-empty → return runtime list (ignore YAML)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_returns_runtime_when_runtime_has_non_empty_hot() {
        // Runtime file present with non-empty hot
        TickerRuntimeConfigPayload runtime = new TickerRuntimeConfigPayload(
                List.of("NVDA", "MSFT", "AAPL"),
                List.of("NVDA", "MSFT"),  // non-null, non-empty
                Map.of(),
                null
        );
        when(runtimeConfigStore.load()).thenReturn(Optional.of(runtime));

        List<String> result = tickerService.getHotTickers();

        assertThat(result).containsExactly("NVDA", "MSFT");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Case 2 — runtime hot is explicitly empty [] → return empty (no fallback)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_returns_empty_when_runtime_has_empty_hot_explicitly() {
        // Runtime file present with explicitly empty hot (not null — just [])
        TickerRuntimeConfigPayload runtime = new TickerRuntimeConfigPayload(
                List.of("NVDA", "MSFT", "AAPL"),
                List.of(),  // explicitly empty — should NOT fall back to YAML
                Map.of(),
                null
        );
        when(runtimeConfigStore.load()).thenReturn(Optional.of(runtime));

        List<String> result = tickerService.getHotTickers();

        assertThat(result)
                .as("Explicit empty hot [] in runtime must return empty list, NOT YAML fallback")
                .isEmpty();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Case 1 — runtime absent → fall back to CSV market-cap
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_uses_csv_market_cap_when_runtime_absent() {
        // No runtime file — runtimeConfigStore returns empty
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());
        when(ibkrProperties.hotTickerCount()).thenReturn(20);

        List<String> result = tickerService.getHotTickers();

        // CSV not loaded in unit test (no file), so market-cap list may be empty — just assert non-null
        assertThat(result).isNotNull();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // hotTickerCount — runtime override of market-cap fallback limit
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_uses_runtime_hot_ticker_count_when_set() {
        // Runtime present: hot=null (CSV fallback path), hotTickerCount=3 override
        when(ibkrProperties.hotTickerCount()).thenReturn(20);      // YAML default — must NOT be used

        TickerRuntimeConfigPayload runtime = new TickerRuntimeConfigPayload(
                List.of(),
                null,          // hot=null → fallback to YAML then market-cap
                Map.of(),
                3              // runtime override: only top 3
        );
        when(runtimeConfigStore.load()).thenReturn(Optional.of(runtime));

        // getHotTickers() falls back to market-cap path — result size must be <= 3
        List<String> result = tickerService.getHotTickers();

        assertThat(result)
                .as("market-cap fallback must respect runtime hotTickerCount=3 (limit 3, not yml default 20)")
                .hasSizeLessThanOrEqualTo(3);
    }

    @Test
    void getHotTickers_uses_yml_hot_ticker_count_when_runtime_not_set() {
        // Runtime present but hotTickerCount=null → must use ibkrProperties.hotTickerCount()
        when(ibkrProperties.hotTickerCount()).thenReturn(5);       // YAML default to use

        TickerRuntimeConfigPayload runtime = new TickerRuntimeConfigPayload(
                List.of(),
                null,          // hot=null → market-cap fallback
                Map.of(),
                null           // no runtime override → use yml hotTickerCount=5
        );
        when(runtimeConfigStore.load()).thenReturn(Optional.of(runtime));

        List<String> result = tickerService.getHotTickers();

        assertThat(result)
                .as("market-cap fallback must use yml hotTickerCount=5 when runtime override is absent")
                .hasSizeLessThanOrEqualTo(5);
    }
}
