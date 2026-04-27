package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.dto.TickerRuntimeConfigPayload;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import com.fgiaquinta.optionsquant.service.TickerRuntimeConfigStore;
import com.fgiaquinta.optionsquant.service.TickerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TickerConfigController} hot-ticker-count endpoints.
 *
 * <p>Contract:
 * <ul>
 *   <li>GET /api/ticker-config/hot-ticker-count → effectiveCount, runtimeOverride, ymlDefault</li>
 *   <li>PUT /api/ticker-config/hot-ticker-count?count=N → 200 when 1–100; 400 otherwise</li>
 * </ul>
 */
class TickerConfigControllerHotTickerCountTest {

    private TickerConfigController controller;
    private TickerRuntimeConfigStore runtimeConfigStore;
    private IbkrProperties ibkrProperties;
    private TickerService tickerService;

    @BeforeEach
    void setUp() {
        tickerService = mock(TickerService.class);
        runtimeConfigStore = mock(TickerRuntimeConfigStore.class);
        ibkrProperties = mock(IbkrProperties.class);
        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);

        controller = new TickerConfigController(
                tickerService, runtimeConfigStore, orderExecutionService, ibkrProperties);
    }

    // ── GET /api/ticker-config/hot-ticker-count ──────────────────────────────

    @Test
    void getHotTickerCount_returns_yml_default_when_no_runtime_override() {
        when(ibkrProperties.hotTickerCount()).thenReturn(20);
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.getHotTickerCount();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("effectiveCount", 20);
        assertThat(resp.getBody()).containsEntry("runtimeOverride", null);
        assertThat(resp.getBody()).containsEntry("ymlDefault", 20);
    }

    @Test
    void getHotTickerCount_returns_runtime_override_when_set() {
        when(ibkrProperties.hotTickerCount()).thenReturn(20);
        TickerRuntimeConfigPayload rt = new TickerRuntimeConfigPayload(List.of(), null, Map.of(), 7);
        when(runtimeConfigStore.load()).thenReturn(Optional.of(rt));

        ResponseEntity<Map<String, Object>> resp = controller.getHotTickerCount();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("effectiveCount", 7);
        assertThat(resp.getBody()).containsEntry("runtimeOverride", 7);
        assertThat(resp.getBody()).containsEntry("ymlDefault", 20);
    }

    // ── PUT /api/ticker-config/hot-ticker-count ──────────────────────────────

    @Test
    void putHotTickerCount_persists_value_and_returns_200() throws IOException {
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.putHotTickerCount(10);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("success", true);
        assertThat(resp.getBody()).containsEntry("effectiveCount", 10);
        verify(runtimeConfigStore).save(any(TickerRuntimeConfigPayload.class));
        verify(tickerService).reload();
    }

    @Test
    void putHotTickerCount_returns_400_when_count_is_zero() throws IOException {
        ResponseEntity<Map<String, Object>> resp = controller.putHotTickerCount(0);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsKey("error");
        verify(runtimeConfigStore, never()).save(any());
    }

    @Test
    void putHotTickerCount_returns_400_when_count_exceeds_100() throws IOException {
        ResponseEntity<Map<String, Object>> resp = controller.putHotTickerCount(101);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsKey("error");
        verify(runtimeConfigStore, never()).save(any());
    }

    @Test
    void putHotTickerCount_boundary_1_is_valid() throws IOException {
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.putHotTickerCount(1);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("effectiveCount", 1);
    }

    @Test
    void putHotTickerCount_boundary_100_is_valid() throws IOException {
        when(runtimeConfigStore.load()).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.putHotTickerCount(100);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("effectiveCount", 100);
    }

    @Test
    void putHotTickerCount_preserves_existing_runtime_universe_and_hot() throws IOException {
        TickerRuntimeConfigPayload existing = new TickerRuntimeConfigPayload(
                List.of("AAPL", "NVDA"), List.of("NVDA"), Map.of(), null);
        when(runtimeConfigStore.load()).thenReturn(Optional.of(existing));

        controller.putHotTickerCount(5);

        // Capture saved payload and verify universe/hot are preserved
        var captor = org.mockito.ArgumentCaptor.forClass(TickerRuntimeConfigPayload.class);
        verify(runtimeConfigStore).save(captor.capture());
        TickerRuntimeConfigPayload saved = captor.getValue();
        assertThat(saved.universe()).containsExactly("AAPL", "NVDA");
        assertThat(saved.hot()).containsExactly("NVDA");
        assertThat(saved.hotTickerCount()).isEqualTo(5);
    }
}
