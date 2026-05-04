package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LiveModeController#executeSignal(String, String, double, String)}
 * (the {@code POST /live-ui/execute-signal} endpoint).
 *
 * <p>The endpoint is a thin delegator to {@code executeTrade}. These tests exercise the
 * delegation explicitly via {@code executeSignal} to lock in the public contract:
 * <ul>
 *   <li>Happy path: fresh signal + connected TWS + successful order → {@code success=true}, includes {@code orderId}.</li>
 *   <li>Stale signal (&gt; 30 min): rejected with {@code stale=true}, no order placed.</li>
 *   <li>No matching live signal: proceeds (no stale guard) and trades.</li>
 *   <li>TWS/Gateway disconnected (and reconnect fails): rejected with TWS-disconnected message.</li>
 *   <li>Trading service throws: caught and surfaced as {@code success=false} with "Internal Error" message.</li>
 *   <li>Trading service returns {@code null}: surfaced as {@code success=false} with execution-failed message.</li>
 * </ul>
 *
 * <p>All responses are 200 OK with a JSON body — the controller never propagates exception
 * stacktraces and never returns non-2xx status for these flows.
 */
class LiveModeControllerExecuteSignalTest {

    private LiveModeController controller;
    private TradingService tradingService;
    private IbkrService ibkrService;
    private AccountManager accountManager;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        StrategyScannerService scannerService = mock(StrategyScannerService.class);
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        TickerService tickerService = mock(TickerService.class);
        accountManager = mock(AccountManager.class);
        ibkrService = mock(IbkrService.class);
        // Default: TWS connected — individual tests override for disconnected scenarios.
        when(ibkrService.isConnected()).thenReturn(true);
        when(accountManager.isConnected()).thenReturn(true);

        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        MarketScanner marketScanner = mock(MarketScanner.class);
        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("executeSignal with fresh live signal and successful order → success=true with orderId")
    void executeSignal_validRequest_returnsSuccessWithOrderId() {
        // Arrange
        StrategyScannerService.Signal fresh = new StrategyScannerService.Signal(
                "AAPL", "squeeze_strat", "CALL", 180.0,
                ZonedDateTime.now().minus(2, ChronoUnit.MINUTES), null);
        controller.addLiveSignal(fresh);

        OrderExecutionService.OrderResult orderResult = mock(OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(42);
        when(tradingService.executeManualTrade(eq("AAPL"), eq("squeeze_strat"), eq("CALL"), eq(180.0)))
                .thenReturn(orderResult);

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("AAPL", "CALL", 180.0, "squeeze_strat");

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("orderId", 42);
        assertThat(body).containsEntry("message", "Order sent to TWS successfully.");
        assertThat(body).containsKey("executeTime");
        verify(tradingService).executeManualTrade("AAPL", "squeeze_strat", "CALL", 180.0);
    }

    // ── Stale signal rejection ───────────────────────────────────────────────

    @Test
    @DisplayName("executeSignal with signal older than 30 minutes → success=false, stale=true, no trade")
    void executeSignal_staleSignal_rejectsExecution() {
        // Arrange
        StrategyScannerService.Signal stale = new StrategyScannerService.Signal(
                "TSLA", "squeeze_strat", "CALL", 250.0,
                ZonedDateTime.now().minus(45, ChronoUnit.MINUTES), null);
        controller.addLiveSignal(stale);

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("TSLA", "CALL", 250.0, "squeeze_strat");

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat(body).containsEntry("stale", true);
        assertThat((String) body.get("message")).contains("older than 30 minutes");
        verifyNoInteractions(tradingService);
    }

    // ── No live signal: proceeds (no stale guard) ────────────────────────────

    @Test
    @DisplayName("executeSignal with no matching live signal → proceeds and places trade")
    void executeSignal_noMatchingLiveSignal_proceedsWithTrade() {
        // Arrange — no controller.addLiveSignal()
        OrderExecutionService.OrderResult orderResult = mock(OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(7);
        when(tradingService.executeManualTrade(eq("NVDA"), eq("squeeze_strat"), eq("PUT"), eq(500.0)))
                .thenReturn(orderResult);

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("NVDA", "PUT", 500.0, "squeeze_strat");

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody()).containsEntry("success", true);
        assertThat(res.getBody()).doesNotContainKey("stale");
        verify(tradingService).executeManualTrade("NVDA", "squeeze_strat", "PUT", 500.0);
    }

    // ── TWS/Gateway disconnected ─────────────────────────────────────────────

    @Test
    @DisplayName("executeSignal when TWS disconnected and reconnect fails → success=false with TWS message, no trade")
    void executeSignal_ibkrDisconnected_returnsErrorWithoutTrading() {
        // Arrange — both connection signals report false; reconnect attempt does not flip them.
        when(ibkrService.isConnected()).thenReturn(false);
        when(accountManager.isConnected()).thenReturn(false);

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("AAPL", "CALL", 180.0, "manual");

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat((String) body.get("message")).contains("TWS/Gateway not connected");
        verify(tradingService).connectAccountManager();
        verify(tradingService, never()).executeManualTrade(anyString(), anyString(), anyString(), anyDouble());
    }

    // ── Order execution throws ───────────────────────────────────────────────

    @Test
    @DisplayName("executeSignal when TradingService throws → success=false with sanitized message and errorId, no exception leak")
    void executeSignal_orderExecutionThrows_returnsErrorWithoutLeakingStacktrace() {
        // Arrange
        when(tradingService.executeManualTrade(anyString(), anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("boom: jdbc://prod-db.internal:5432 connection refused"));

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("AAPL", "CALL", 180.0, "manual");

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        // Lock the new sanitized contract — exact match, no internal details.
        assertThat(body).containsEntry("message", "Trade execution failed — see logs.");
        // The exception message must NEVER be concatenated into the response.
        assertThat((String) body.get("message")).doesNotContain("boom");
        assertThat((String) body.get("message")).doesNotContain("jdbc");
        assertThat((String) body.get("message")).doesNotContain("at com.fgiaquinta.optionsquant");
        // errorId must be present so ops can correlate logs ↔ client report.
        assertThat(body).containsKey("errorId");
        assertThat((String) body.get("errorId")).isNotBlank();
    }

    // ── Order execution returns null ─────────────────────────────────────────

    @Test
    @DisplayName("executeSignal when TradingService returns null → success=false with execution-failed message")
    void executeSignal_orderExecutionReturnsNull_returnsFailureBody() {
        // Arrange
        when(tradingService.executeManualTrade(anyString(), anyString(), anyString(), anyDouble()))
                .thenReturn(null);

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("AAPL", "CALL", 180.0, "manual");

        // Assert
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat(body).containsEntry("message", "Trade execution failed in IBKR service.");
        assertThat(body).doesNotContainKey("orderId");
    }
}
