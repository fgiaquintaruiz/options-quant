package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
 *   <li>Runtime present, {@code hot} non-empty → return runtime list (ignore YAML)</li>
 *   <li>Runtime present, {@code hot} explicitly empty {@code []} → return empty (no YAML fallback)</li>
 *   <li>Runtime present, {@code hot == null} → fall back to YAML</li>
 * </ol>
 */
@ExtendWith(OutputCaptureExtension.class)
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
        // YAML fallback has [AAPL]
        when(ibkrProperties.hotTickers()).thenReturn(List.of("AAPL"));
        when(ibkrProperties.universeTickers()).thenReturn(List.of("NVDA", "MSFT", "AAPL"));

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
        // YAML fallback has [AAPL] — must NOT be used
        when(ibkrProperties.hotTickers()).thenReturn(List.of("AAPL"));
        when(ibkrProperties.universeTickers()).thenReturn(List.of("NVDA", "MSFT", "AAPL"));

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
    // Case 1 — runtime hot is null → fall back to YAML
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_falls_back_to_yaml_when_runtime_hot_is_null() {
        // YAML fallback has [AAPL]
        when(ibkrProperties.hotTickers()).thenReturn(List.of("AAPL"));
        when(ibkrProperties.universeTickers()).thenReturn(List.of("NVDA", "MSFT", "AAPL"));

        // Runtime file present but hot field is null (field absent in JSON)
        TickerRuntimeConfigPayload runtime = new TickerRuntimeConfigPayload(
                List.of("NVDA", "MSFT", "AAPL"),
                null,   // null → must fall back to YAML
                Map.of(),
                null
        );
        when(runtimeConfigStore.load()).thenReturn(Optional.of(runtime));

        List<String> result = tickerService.getHotTickers();

        assertThat(result).containsExactly("AAPL");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Task 2.3 — INFO log on YAML fallback
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_logs_INFO_when_falling_back_to_yaml(CapturedOutput output) {
        when(ibkrProperties.hotTickers()).thenReturn(List.of("AAPL"));
        when(ibkrProperties.universeTickers()).thenReturn(List.of("NVDA", "MSFT", "AAPL"));

        // Runtime present but hot is null → triggers YAML fallback + INFO log
        TickerRuntimeConfigPayload runtime = new TickerRuntimeConfigPayload(
                List.of("NVDA", "MSFT", "AAPL"),
                null,
                Map.of(),
                null
        );
        when(runtimeConfigStore.load()).thenReturn(Optional.of(runtime));

        tickerService.getHotTickers();

        assertThat(output.getOut())
                .as("Expected INFO log containing 'HOT bootstrapped from YAML'")
                .contains("HOT bootstrapped from YAML");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // hotTickerCount — runtime override of market-cap fallback limit
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    void getHotTickers_uses_runtime_hot_ticker_count_when_set() {
        // Runtime present: hot=null (YAML fallback path), hotTickerCount=3 override
        when(ibkrProperties.hotTickers()).thenReturn(List.of());   // YAML hot empty → market-cap path
        when(ibkrProperties.universeTickers()).thenReturn(List.of());
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
        when(ibkrProperties.hotTickers()).thenReturn(List.of());   // YAML hot empty → market-cap path
        when(ibkrProperties.universeTickers()).thenReturn(List.of());
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
