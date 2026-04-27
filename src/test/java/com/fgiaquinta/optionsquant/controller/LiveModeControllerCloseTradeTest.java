package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the close-trade endpoint of {@link LiveModeController}.
 * Verifies the two-branch behaviour: local close vs. TP/SL cancellation in IBKR.
 */
class LiveModeControllerCloseTradeTest {

    private LiveModeController controller;
    private OrderExecutionService orderExecutionService;

    @BeforeEach
    void setUp() {
        // All dependencies mocked — we only exercise the controller logic.
        final StrategyScannerService scannerService = mock(StrategyScannerService.class);
        final IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        final TradingService tradingService = mock(TradingService.class);
        final TickerService tickerService = mock(TickerService.class);
        final AccountManager accountManager = mock(AccountManager.class);
        final IbkrService ibkrService = mock(IbkrService.class);
        final MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        final MarketScanner marketScanner = mock(MarketScanner.class);
        this.orderExecutionService = mock(OrderExecutionService.class);
        final ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);

        this.controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );
    }

    @Test
    @DisplayName("closeTrade without TP/SL order IDs only marks position as closed locally")
    void closeTrade_withoutOrderIds_shouldOnlyMarkLocally() {
        // GIVEN a simple close request (no IBKR order IDs)
        final String ticker = "AAPL";
        final double price = 150.25;

        // WHEN closing
        final ResponseEntity<Map<String, Object>> response =
                controller.closeTrade(ticker, price, null, null);

        // THEN
        assertEquals(200, response.getStatusCode().value(), "HTTP status should be 200");
        final Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(true, body.get("success"));
        assertEquals(ticker, body.get("ticker"));
        assertEquals(price, body.get("closePrice"));

        // AND no IBKR cancellation should have been attempted
        verifyNoInteractions(orderExecutionService);
    }

    @Test
    @DisplayName("closeTrade with tpOrderId only cancels the TP order in IBKR")
    void closeTrade_withTpOnly_shouldCancelTp() {
        // GIVEN a close request with only the TP order ID
        final String ticker = "MSFT";
        final double price = 420.0;
        final Integer tpOrderId = 555;

        // WHEN closing
        final ResponseEntity<Map<String, Object>> response =
                controller.closeTrade(ticker, price, tpOrderId, null);

        // THEN the TP order must be cancelled in IBKR (and only the TP — no SL cancel)
        verify(orderExecutionService).connect();
        verify(orderExecutionService).cancelOrder(tpOrderId);
        verify(orderExecutionService, times(1)).cancelOrder(anyInt());
        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, response.getBody().get("success"));
    }

    @Test
    @DisplayName("closeTrade with both TP and SL order IDs cancels both in IBKR")
    void closeTrade_withBothOrderIds_shouldCancelBoth() {
        // GIVEN a close request with both TP and SL order IDs
        final String ticker = "NVDA";
        final double price = 890.5;
        final Integer tpOrderId = 701;
        final Integer slOrderId = 702;

        // WHEN closing
        final ResponseEntity<Map<String, Object>> response =
                controller.closeTrade(ticker, price, tpOrderId, slOrderId);

        // THEN both orders must be cancelled and the position marked closed
        verify(orderExecutionService).connect();
        verify(orderExecutionService).cancelOrder(tpOrderId);
        verify(orderExecutionService).cancelOrder(slOrderId);

        final Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("success"));
        assertEquals("TP/SL orders cancelled - position closed", body.get("message"));
    }

    @Test
    @DisplayName("closeTrade falls back to local close when IBKR cancellation throws")
    void closeTrade_whenCancelThrows_fallsBackToLocalClose() {
        // GIVEN cancellation throws (e.g. TWS disconnected)
        final String ticker = "TSLA";
        final double price = 175.0;
        final Integer tpOrderId = 999;

        doThrow(new RuntimeException("TWS disconnected")).when(orderExecutionService).cancelOrder(anyInt());

        // WHEN closing
        final ResponseEntity<Map<String, Object>> response =
                controller.closeTrade(ticker, price, tpOrderId, null);

        // THEN fallback to local close — endpoint must not 500
        assertEquals(200, response.getStatusCode().value());
        final Map<String, Object> body = response.getBody();
        assertEquals(true, body.get("success"));
        assertEquals("Trade marked as closed at " + price, body.get("message"));
    }
}
