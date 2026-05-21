package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LiveModeControllerExecuteTradeTest {

    private LiveModeController controller;
    private TradingService tradingService;
    private IbkrService ibkrService;
    private AccountManager accountManager;
    private MarketCalendarService marketCalendarService;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        ibkrService = mock(IbkrService.class);
        accountManager = mock(AccountManager.class);
        marketCalendarService = mock(MarketCalendarService.class);

        final StrategyScannerService scannerService = mock(StrategyScannerService.class);
        final IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        final TickerService tickerService = mock(TickerService.class);
        final OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        final MarketScanner marketScanner = mock(MarketScanner.class);
        final MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);

        final ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        // Default: market is open so existing tests continue to pass
        when(marketCalendarService.isMarketOpenNow()).thenReturn(true);

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );
    }

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("executeTrade — no signal, TWS connected, execution succeeds → 200 OK, success=true, orderId present")
    void executeTrade_noSignal_twsConnected_executionSucceeds_returnsOrderId() {
        when(ibkrService.isConnected()).thenReturn(true);
        OrderExecutionService.OrderResult orderResult =
                new OrderExecutionService.OrderResult(42, 43, 44, 0.0, null, null);
        when(tradingService.executeManualTrade("AAPL", "manual", "BUY", 150.0))
                .thenReturn(orderResult);

        ResponseEntity<Map<String, Object>> res = controller.executeTrade("AAPL", "BUY", 150.0, "manual");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("orderId", 42);
        assertThat(body.get("executeTime")).isNotNull();
    }

    // ── TWS fully disconnected ───────────────────────────────────────────────

    @Test
    @DisplayName("executeTrade — both ibkrService and accountManager disconnected, stay disconnected after reconnect → 200 OK, success=false, message contains 'not connected'")
    void executeTrade_twsFullyDisconnected_returnsNotConnected() {
        when(ibkrService.isConnected()).thenReturn(false);
        when(accountManager.isConnected()).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.executeTrade("AAPL", "BUY", 150.0, "manual");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat((String) body.get("message")).containsIgnoringCase("not connected");
        verify(tradingService).connectAccountManager();
        verify(tradingService, never()).executeManualTrade(any(), any(), any(), anyDouble());
    }

    // ── Null OrderResult ─────────────────────────────────────────────────────

    @Test
    @DisplayName("executeTrade — TWS connected, executeManualTrade returns null → 200 OK, success=false, message contains 'failed'")
    void executeTrade_nullOrderResult_returnsFailure() {
        when(ibkrService.isConnected()).thenReturn(true);
        when(tradingService.executeManualTrade(anyString(), anyString(), anyString(), anyDouble()))
                .thenReturn(null);

        ResponseEntity<Map<String, Object>> res = controller.executeTrade("TSLA", "CALL", 200.0, "manual");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat((String) body.get("message")).containsIgnoringCase("failed");
    }

    // ── Stale signal blocks execution ────────────────────────────────────────

    @Test
    @DisplayName("executeTrade — signal older than 30 min → 200 OK, success=false, stale=true, message contains '30 minutes'")
    void executeTrade_staleSignal_blocksExecution() {
        Signal staleSignal = new Signal(
                "AAPL", "manual", "BUY", 150.0,
                ZonedDateTime.now().minusMinutes(31),
                null
        );
        controller.addLiveSignal(staleSignal);

        ResponseEntity<Map<String, Object>> res = controller.executeTrade("AAPL", "BUY", 150.0, "manual");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat(body).containsEntry("stale", true);
        assertThat((String) body.get("message")).contains("30 minutes");
        verify(tradingService, never()).executeManualTrade(any(), any(), any(), anyDouble());
    }

    // ── Fresh signal does NOT block ──────────────────────────────────────────

    @Test
    @DisplayName("executeTrade — fresh signal (just now) → does NOT block, success=true when TWS connected")
    void executeTrade_freshSignal_doesNotBlock() {
        Signal freshSignal = new Signal(
                "AAPL", "manual", "BUY", 150.0,
                ZonedDateTime.now(),
                null
        );
        controller.addLiveSignal(freshSignal);

        when(ibkrService.isConnected()).thenReturn(true);
        OrderExecutionService.OrderResult orderResult =
                new OrderExecutionService.OrderResult(99, 100, 101, 0.0, null, null);
        when(tradingService.executeManualTrade("AAPL", "manual", "BUY", 150.0))
                .thenReturn(orderResult);

        ResponseEntity<Map<String, Object>> res = controller.executeTrade("AAPL", "BUY", 150.0, "manual");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
    }

    // ── Exception → sanitized error, no stack trace leak ────────────────────

    @Test
    @DisplayName("executeTrade — executeManualTrade throws RuntimeException → 200 OK, success=false, body contains errorId, no jdbc/Exception leak")
    void executeTrade_exceptionThrown_returnsErrorIdNoLeakage() {
        when(ibkrService.isConnected()).thenReturn(true);
        when(tradingService.executeManualTrade(anyString(), anyString(), anyString(), anyDouble()))
                .thenThrow(new RuntimeException("internal db error at jdbc://localhost:5432/prod"));

        ResponseEntity<Map<String, Object>> res = controller.executeTrade("NVDA", "PUT", 400.0, "manual");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat(body).containsKey("errorId");

        String bodyStr = body.toString();
        assertThat(bodyStr).doesNotContain("jdbc");
        assertThat(bodyStr).doesNotContain("Exception");
        assertThat(bodyStr).doesNotContain("localhost");
        assertThat(bodyStr).doesNotContain("at com.fgiaquinta.optionsquant");
        assertThat(bodyStr).doesNotContain("StackTrace");
    }

    // ── Bug 1: market closed blocks execution ────────────────────────────────

    @Test
    @DisplayName("executeTrade — market closed → 400, success=false, error='Market is closed'")
    void whenMarketClosed_executeSignal_returns400WithClearMessage() {
        when(marketCalendarService.isMarketOpenNow()).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.executeTrade("AAPL", "BUY", 150.0, "manual");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat(body).containsEntry("error", "Market is closed");
        verify(tradingService, never()).executeManualTrade(any(), any(), any(), anyDouble());
    }
}
