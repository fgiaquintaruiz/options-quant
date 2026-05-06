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

    // ── P1: Signal age boundary — just inside 30-minute window ───────────────

    @Test
    @DisplayName("executeSignal with signal timestamp 29m59s old → NOT stale (boundary: < 30min is always fresh)")
    void executeSignal_signalExactly30MinutesOld_notRejectedAsStale() {
        // Arrange
        // isLiveSignalOlderThanMaxAge uses: timestamp.isBefore(now - 30min)
        // The boundary is exclusive: strictly > 30min is stale; <= 30min is not stale.
        // Using now-30min+1s to avoid the inherent timing race of an exact boundary assertion —
        // a signal 1 second inside the window is deterministically NOT stale under any realistic clock drift.
        StrategyScannerService.Signal boundary = new StrategyScannerService.Signal(
                "AMZN", "squeeze_strat", "CALL", 200.0,
                ZonedDateTime.now().minus(30, ChronoUnit.MINUTES).plusSeconds(1), null);
        controller.addLiveSignal(boundary);

        OrderExecutionService.OrderResult orderResult = mock(OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(55);
        when(tradingService.executeManualTrade(eq("AMZN"), eq("squeeze_strat"), eq("CALL"), eq(200.0)))
                .thenReturn(orderResult);

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("AMZN", "CALL", 200.0, "squeeze_strat");

        // Assert — signal just inside the 30-min window must NOT be rejected as stale
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).doesNotContainKey("stale");
        assertThat(body).containsEntry("success", true);
        verify(tradingService).executeManualTrade("AMZN", "squeeze_strat", "CALL", 200.0);
    }

    // ── P2: TWS reconnect succeeds → trade executes ───────────────────────────

    @Test
    @DisplayName("executeSignal: both connections fail, reconnect called, second check passes → trade executes")
    void executeSignal_reconnectSucceeds_tradeExecutes() {
        // Arrange
        // First check: both disconnected — triggers connectAccountManager().
        // After reconnect: accountManager becomes connected (ibkrService still false).
        // The second guard: !ibkrService.isConnected() && !accountManager.isConnected()
        // → false (accountManager is now connected) → proceeds to trade.
        when(ibkrService.isConnected()).thenReturn(false);
        when(accountManager.isConnected())
                .thenReturn(false)   // first call: triggers reconnect
                .thenReturn(true);   // second call: reconnect succeeded

        OrderExecutionService.OrderResult orderResult = mock(OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(99);
        when(tradingService.executeManualTrade(eq("MSFT"), eq("manual"), eq("PUT"), eq(300.0)))
                .thenReturn(orderResult);

        // Act
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("MSFT", "PUT", 300.0, "manual");

        // Assert — reconnect path: connectAccountManager must be called and trade must execute
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("orderId", 99);
        verify(tradingService).connectAccountManager();
        verify(tradingService).executeManualTrade("MSFT", "manual", "PUT", 300.0);
    }

    // ── P3: Explicit request price takes precedence over signal price ─────────

    @Test
    @DisplayName("executeSignal: explicit price param used for trade, not the signal's currentPrice")
    void executeSignal_explicitPriceTakesPrecedenceOverSignalPrice() {
        // Arrange — signal has currentPrice=182.0, request sends price=180.0
        StrategyScannerService.Signal signal = new StrategyScannerService.Signal(
                "AAPL", "squeeze_strat", "CALL", 182.0,
                ZonedDateTime.now().minus(1, ChronoUnit.MINUTES), null);
        controller.addLiveSignal(signal);

        OrderExecutionService.OrderResult orderResult = mock(OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(77);
        // Production code calls: executeManualTrade(ticker, strategy, direction, price)
        // where price = request param (not signal.currentPrice()).
        when(tradingService.executeManualTrade(eq("AAPL"), eq("squeeze_strat"), eq("CALL"), eq(180.0)))
                .thenReturn(orderResult);

        // Act — explicit price=180.0 in request
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("AAPL", "CALL", 180.0, "squeeze_strat");

        // Assert — trade must be placed with request price 180.0, not signal price 182.0
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        verify(tradingService).executeManualTrade("AAPL", "squeeze_strat", "CALL", 180.0);
        verify(tradingService, never()).executeManualTrade(eq("AAPL"), eq("squeeze_strat"), eq("CALL"), eq(182.0));
    }

    // ── P4: Null strategy param matches any live signal ───────────────────────

    @Test
    @DisplayName("executeSignal: strategy=manual (default) matches any signal regardless of its strategy name")
    void executeSignal_manualStrategyMatchesAnyLiveSignal() {
        // Arrange — signal has a specific strategy; request sends strategy="manual" (the @RequestParam default)
        // findLiveSignalForExecute: strategyParam == null || "manual".equalsIgnoreCase(strategyParam) → returns signal
        StrategyScannerService.Signal signal = new StrategyScannerService.Signal(
                "GOOG", "c1_squeeze_breakout", "CALL", 150.0,
                ZonedDateTime.now().minus(3, ChronoUnit.MINUTES), null);
        controller.addLiveSignal(signal);

        OrderExecutionService.OrderResult orderResult = mock(OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(33);
        when(tradingService.executeManualTrade(eq("GOOG"), eq("manual"), eq("CALL"), eq(150.0)))
                .thenReturn(orderResult);

        // Act — strategy="manual" (default value) should match the "c1_squeeze_breakout" live signal
        ResponseEntity<Map<String, Object>> res =
                controller.executeSignal("GOOG", "CALL", 150.0, "manual");

        // Assert — signal found (stale guard applied) and trade proceeds
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        // The strategy forwarded to tradingService is the REQUEST param ("manual"), not the signal's strategy
        verify(tradingService).executeManualTrade("GOOG", "manual", "CALL", 150.0);
    }

    // ── P5: Duplicate execute — second call after first succeeds ─────────────

    @Test
    @DisplayName("executeSignal called twice for same ticker: signal stays in list, second call also executes")
    void executeSignal_duplicateCall_secondCallAlsoExecutes() {
        // Arrange — signal is NOT removed from liveSignals after first execution.
        // Second call finds the same signal (still fresh), stale guard passes, trade executes again.
        StrategyScannerService.Signal signal = new StrategyScannerService.Signal(
                "SPY", "squeeze_strat", "CALL", 500.0,
                ZonedDateTime.now().minus(1, ChronoUnit.MINUTES), null);
        controller.addLiveSignal(signal);

        OrderExecutionService.OrderResult firstResult = mock(OrderExecutionService.OrderResult.class);
        when(firstResult.parentId()).thenReturn(101);
        OrderExecutionService.OrderResult secondResult = mock(OrderExecutionService.OrderResult.class);
        when(secondResult.parentId()).thenReturn(102);
        when(tradingService.executeManualTrade(eq("SPY"), eq("squeeze_strat"), eq("CALL"), eq(500.0)))
                .thenReturn(firstResult)
                .thenReturn(secondResult);

        // Act — first call
        ResponseEntity<Map<String, Object>> res1 =
                controller.executeSignal("SPY", "CALL", 500.0, "squeeze_strat");
        // Act — second call (same ticker, same params)
        ResponseEntity<Map<String, Object>> res2 =
                controller.executeSignal("SPY", "CALL", 500.0, "squeeze_strat");

        // Assert — both calls succeed; signal is not removed between calls
        assertThat(res1.getBody()).containsEntry("success", true);
        assertThat(res1.getBody()).containsEntry("orderId", 101);
        assertThat(res2.getBody()).containsEntry("success", true);
        assertThat(res2.getBody()).containsEntry("orderId", 102);
        verify(tradingService, times(2)).executeManualTrade("SPY", "squeeze_strat", "CALL", 500.0);
    }
}
