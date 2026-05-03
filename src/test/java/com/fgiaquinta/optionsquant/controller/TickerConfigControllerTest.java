package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TickerInfo;
import com.fgiaquinta.optionsquant.dto.TickerFundamentalPayload;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import com.fgiaquinta.optionsquant.service.TickerRuntimeConfigStore;
import com.fgiaquinta.optionsquant.service.TickerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
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
 *   <li>PUT /api/ticker-config → persists payload, reloads tickers, returns success body</li>
 *   <li>GET /api/ticker-config/validate?symbol=X → delegates to OrderExecutionService, never throws</li>
 *   <li>DELETE /api/ticker-config/symbol/{symbol} → removes from universe/hot/fundamentals, persists, reloads</li>
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

    // ── PUT /api/ticker-config ───────────────────────────────────────────────

    @Test
    void putConfig_persists_payload_reloads_and_returns_success_body() throws IOException {
        TickerRuntimeConfigPayload payload = new TickerRuntimeConfigPayload(
                List.of("AAPL", "MSFT"), List.of("AAPL"), Map.of(), null);
        when(tickerService.getTickerSymbols()).thenReturn(List.of("AAPL", "MSFT"));
        when(tickerService.getHotTickers()).thenReturn(List.of("AAPL"));

        ResponseEntity<Map<String, Object>> resp = controller.putConfig(payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", true);
        assertThat((List<String>) body.get("universe")).containsExactly("AAPL", "MSFT");
        assertThat((List<String>) body.get("hot")).containsExactly("AAPL");
        verify(runtimeConfigStore).save(payload);
        verify(tickerService).reload();
    }

    // ── GET /api/ticker-config/validate ──────────────────────────────────────

    @Test
    void validateTicker_returns_valid_true_when_tws_confirms() {
        when(orderExecutionService.validateTicker("AAPL")).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.validateTicker("AAPL");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("valid", true);
    }

    // ── DELETE /api/ticker-config/symbol/{symbol} ────────────────────────────

    @Test
    void deleteSymbol_removes_symbol_from_universe_hot_and_fundamentals_then_reloads() throws IOException {
        Map<String, TickerFundamentalPayload> fundamentals = new LinkedHashMap<>();
        fundamentals.put("AAPL", new TickerFundamentalPayload(
                "Apple Inc.", "Tech", 3000L, 28.5, 0.5, 1.2, 8.0, 7.0, 0.4, 25.0, null));
        fundamentals.put("NVDA", new TickerFundamentalPayload(
                "NVIDIA", "Tech", 1500L, 60.0, 0.0, 1.6, 30.0, 25.0, 0.2, 30.0, null));
        TickerRuntimeConfigPayload existing = new TickerRuntimeConfigPayload(
                List.of("AAPL", "NVDA", "MSFT"), List.of("NVDA", "MSFT"), fundamentals, 10);
        when(runtimeConfigStore.load()).thenReturn(Optional.of(existing));

        ResponseEntity<Map<String, Object>> resp = controller.deleteSymbol("nvda");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("success", true);
        assertThat(resp.getBody()).containsEntry("deleted", "NVDA");

        var captor = org.mockito.ArgumentCaptor.forClass(TickerRuntimeConfigPayload.class);
        verify(runtimeConfigStore).save(captor.capture());
        TickerRuntimeConfigPayload saved = captor.getValue();
        assertThat(saved.universe()).containsExactly("AAPL", "MSFT");
        assertThat(saved.hot()).containsExactly("MSFT");
        assertThat(saved.fundamentals()).containsOnlyKeys("AAPL");
        assertThat(saved.hotTickerCount()).isEqualTo(10);
        verify(tickerService).reload();
    }
}
