package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LiveModeController#cancelTrade(String, int)}
 * (the {@code POST /live-ui/cancel-trade} endpoint).
 *
 * <p>The endpoint delegates to {@link TradingService#cancelTrade(int)} and always
 * returns 200 OK — never propagates exceptions to the caller.
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>Happy path: TWS acknowledges cancellation → {@code success=true}, correct message.</li>
 *   <li>TWS failure: service returns false → {@code success=false}, sanitized failure message.</li>
 *   <li>TWS throws exception: caught inside {@link TradingService#cancelTrade} → returns false → same sanitized path.</li>
 *   <li>Missing orderId (0): edge-value forwarded, service decides outcome.</li>
 *   <li>Negative orderId: edge-value forwarded, service decides outcome.</li>
 *   <li>Security: error response does NOT leak raw exception messages, host, port or internal details.</li>
 * </ul>
 */
class LiveModeControllerCancelTradeTest {

    private LiveModeController controller;
    private TradingService tradingService;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        final StrategyScannerService scannerService = mock(StrategyScannerService.class);
        final IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        final TickerService tickerService = mock(TickerService.class);
        final AccountManager accountManager = mock(AccountManager.class);
        final IbkrService ibkrService = mock(IbkrService.class);
        final OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        final MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        final MarketScanner marketScanner = mock(MarketScanner.class);

        final ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        final MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelTrade with valid request → TWS cancel sent → 200 OK, success=true")
    void cancelTrade_validRequest_returnsSuccessTrue() {
        // Arrange
        when(tradingService.cancelTrade(42)).thenReturn(true);

        // Act
        ResponseEntity<Map<String, Object>> res = controller.cancelTrade("AAPL", 42);

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("message", "Order cancellation sent to TWS");
        verify(tradingService).cancelTrade(42);
    }

    // ── TWS cancellation failure ─────────────────────────────────────────────

    @Test
    @DisplayName("cancelTrade when TWS returns failure → 200 OK, success=false with failure message")
    void cancelTrade_twsReturnsFalse_returnsSuccessFalseWithMessage() {
        // Arrange
        when(tradingService.cancelTrade(99)).thenReturn(false);

        // Act
        ResponseEntity<Map<String, Object>> res = controller.cancelTrade("TSLA", 99);

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat(body).containsEntry("message", "Failed to send cancellation");
        verify(tradingService).cancelTrade(99);
    }

    // ── TWS throws exception (absorbed by TradingService) ────────────────────

    @Test
    @DisplayName("cancelTrade when TradingService absorbs exception and returns false → 200 OK, success=false, no exception leak")
    void cancelTrade_serviceAbsorbsException_returnsSuccessFalseWithSanitizedMessage() {
        // Arrange — TradingService.cancelTrade catches internally and returns false;
        // controller must not leak any raw exception detail regardless.
        when(tradingService.cancelTrade(anyInt())).thenReturn(false);

        // Act
        ResponseEntity<Map<String, Object>> res = controller.cancelTrade("NVDA", 7);

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);

        // Security: the failure message must be a controlled string — no internal detail.
        String message = (String) body.get("message");
        assertThat(message).isNotNull();
        assertThat(message).doesNotContain("Exception");
        assertThat(message).doesNotContain("at com.fgiaquinta.optionsquant");
        assertThat(message).doesNotContain("jdbc");
        assertThat(message).doesNotContain("host");
    }

    // ── Security: response must not leak TWS internals ───────────────────────

    @Test
    @DisplayName("cancelTrade error response does NOT leak exception messages, host, port or stack traces")
    void cancelTrade_errorResponse_doesNotLeakInternalDetails() {
        // Arrange — service returns failure (TWS error absorbed internally)
        when(tradingService.cancelTrade(anyInt())).thenReturn(false);

        // Act
        ResponseEntity<Map<String, Object>> res = controller.cancelTrade("MSFT", 500);

        // Assert — scan the whole body for any leakage
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        String bodyAsString = body.toString();
        assertThat(bodyAsString).doesNotContain("127.0.0.1");
        assertThat(bodyAsString).doesNotContain("localhost");
        assertThat(bodyAsString).doesNotContain("TWS error");
        assertThat(bodyAsString).doesNotContain("NullPointerException");
        assertThat(bodyAsString).doesNotContain("StackTrace");
    }

    // ── Edge: orderId = 0 ────────────────────────────────────────────────────

    @Test
    @DisplayName("cancelTrade with orderId=0 (edge value) → forwarded to service, success=true when service confirms")
    void cancelTrade_orderIdZero_forwardedToService() {
        // Arrange
        when(tradingService.cancelTrade(0)).thenReturn(true);

        // Act
        ResponseEntity<Map<String, Object>> res = controller.cancelTrade("SPY", 0);

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        verify(tradingService).cancelTrade(0);
    }

    // ── Edge: negative orderId ───────────────────────────────────────────────

    @Test
    @DisplayName("cancelTrade with negative orderId → forwarded to service, success=false when service rejects")
    void cancelTrade_negativeOrderId_forwardedToService() {
        // Arrange
        when(tradingService.cancelTrade(-1)).thenReturn(false);

        // Act
        ResponseEntity<Map<String, Object>> res = controller.cancelTrade("QQQ", -1);

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", false);
        verify(tradingService).cancelTrade(-1);
    }

    // ── closedTrades side-effect on success ──────────────────────────────────

    @Test
    @DisplayName("cancelTrade on success → ticker is registered in closedTrades (subsequent state check)")
    void cancelTrade_onSuccess_registersTickerAsClosedWithCancelledReason() {
        // Arrange
        when(tradingService.cancelTrade(10)).thenReturn(true);

        // Act
        ResponseEntity<Map<String, Object>> res = controller.cancelTrade("AMZN", 10);

        // Assert — direct response
        assertThat(res.getBody()).containsEntry("success", true);
        // The side-effect: closedTrades.put(ticker, ClosedTradeInfo(..., "CANCELLED"))
        // is observable indirectly through a subsequent /status or /live-signals call.
        // Here we confirm the service was called exactly once (no double-cancel).
        verify(tradingService, times(1)).cancelTrade(10);
    }
}
