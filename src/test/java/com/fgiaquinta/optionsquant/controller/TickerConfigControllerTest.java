package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import com.fgiaquinta.optionsquant.service.TickerRuntimeConfigStore;
import com.fgiaquinta.optionsquant.service.TickerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TickerConfigController} core ABM endpoints.
 *
 * <p>Contract:
 * <ul>
 *   <li>GET /api/ticker-config → universe + hot + entries + fundamentals + flags</li>
 * </ul>
 */
class TickerConfigControllerTest {

    private TickerConfigController controller;
    private TickerService tickerService;
    private TickerRuntimeConfigStore runtimeConfigStore;
    private OrderExecutionService orderExecutionService;
    private IbkrProperties ibkrProperties;

    @BeforeEach
    void setUp() {
        tickerService = mock(TickerService.class);
        runtimeConfigStore = mock(TickerRuntimeConfigStore.class);
        orderExecutionService = mock(OrderExecutionService.class);
        ibkrProperties = mock(IbkrProperties.class);

        controller = new TickerConfigController(
                tickerService, runtimeConfigStore, orderExecutionService, ibkrProperties);
    }

    // ── GET /api/ticker-config ───────────────────────────────────────────────

    @Test
    void getConfig_returns_universe_hot_entries_and_runtime_flags() {
        when(tickerService.getTickerSymbols()).thenReturn(List.of("AAPL", "NVDA"));
        when(tickerService.getHotTickers()).thenReturn(List.of("NVDA"));
        Map<String, TickerInfo> snapshot = new LinkedHashMap<>();
        snapshot.put("AAPL", new TickerInfo(
                "AAPL", "Apple Inc.", "Tech", 3000L, 28.5, 0.5, 1.2, 8.0, 7.0, 0.4, 25.0, null));
        when(tickerService.snapshotMapForConfigApi()).thenReturn(snapshot);
        TickerRuntimeConfigPayload rt = new TickerRuntimeConfigPayload(
                List.of("AAPL", "NVDA"), List.of("NVDA"), Map.of(), null);
        when(runtimeConfigStore.load()).thenReturn(Optional.of(rt));

        ResponseEntity<Map<String, Object>> resp = controller.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("universe", "hot", "entries", "fundamentals", "hasRuntimeFile");
        assertThat((List<String>) body.get("universe")).containsExactly("AAPL", "NVDA");
        assertThat((List<String>) body.get("hot")).containsExactly("NVDA");
        assertThat((Map<String, ?>) body.get("entries")).containsKey("AAPL");
        assertThat(body.get("fundamentals")).isEqualTo(body.get("entries"));
        assertThat(body).containsEntry("hasRuntimeFile", true);
        assertThat(body).containsEntry("runtimeUniverseEmpty", false);
        assertThat(body).containsEntry("runtimeHotEmpty", false);
        verify(tickerService).loadTickers();
    }
}
