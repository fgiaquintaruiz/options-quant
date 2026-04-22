package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Manual execute must reject live rows whose signal timestamp is older than 15 minutes (server clock).
 */
class LiveModeControllerStaleSignalTest {

    private LiveModeController controller;
    private TradingService tradingService;

    @BeforeEach
    void setUp() {
        tradingService = mock(TradingService.class);
        StrategyScannerService scannerService = mock(StrategyScannerService.class);
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        TickerService tickerService = mock(TickerService.class);
        AccountManager accountManager = mock(AccountManager.class);
        IbkrService ibkrService = mock(IbkrService.class);
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

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties
        );
    }

    @Test
    @DisplayName("executeTrade returns stale=true and does not call TradingService when signal timestamp is >15m old")
    void executeTrade_blocksStaleSignal() {
        StrategyScannerService.Signal stale = new StrategyScannerService.Signal(
                "AAPL",
                "squeeze_strat",
                "CALL",
                180.0,
                ZonedDateTime.now().minus(20, ChronoUnit.MINUTES),
                null
        );
        controller.addLiveSignal(stale);

        ResponseEntity<Map<String, Object>> res =
                controller.executeTrade("AAPL", "CALL", 180.0, "squeeze_strat");

        assertEquals(200, res.getStatusCode().value());
        Map<String, Object> body = res.getBody();
        assertNotNull(body);
        assertEquals(Boolean.FALSE, body.get("success"));
        assertEquals(Boolean.TRUE, body.get("stale"));
        verifyNoInteractions(tradingService);
    }

    @Test
    @DisplayName("executeTrade proceeds when signal timestamp is within 15 minutes")
    void executeTrade_allowsFreshSignal() {
        StrategyScannerService.Signal fresh = new StrategyScannerService.Signal(
                "MSFT",
                "squeeze_strat",
                "PUT",
                400.0,
                ZonedDateTime.now().minus(2, ChronoUnit.MINUTES),
                null
        );
        controller.addLiveSignal(fresh);
        OrderExecutionService.OrderResult orderResult = mock(OrderExecutionService.OrderResult.class);
        when(orderResult.parentId()).thenReturn(999);
        when(tradingService.executeManualTrade(anyString(), anyString(), anyString(), anyDouble()))
                .thenReturn(orderResult);

        ResponseEntity<Map<String, Object>> res =
                controller.executeTrade("MSFT", "PUT", 400.0, "squeeze_strat");

        assertNotNull(res.getBody());
        assertEquals(Boolean.TRUE, res.getBody().get("success"));
        verify(tradingService).executeManualTrade(eq("MSFT"), eq("squeeze_strat"), eq("PUT"), eq(400.0));
    }
}
