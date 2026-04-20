package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LiveModeController#getStatus()} JSON shape.
 */
class LiveModeControllerStatusTest {

    private LiveModeController controller;

    @BeforeEach
    void setUp() {
        StrategyScannerService scannerService = mock(StrategyScannerService.class);
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);
        when(ibkrProperties.riskPerTradePct()).thenReturn(0.02);
        when(ibkrProperties.hotTickers()).thenReturn(List.of("SPY", "QQQ"));

        TradingService tradingService = mock(TradingService.class);
        TickerService tickerService = mock(TickerService.class);
        AccountManager accountManager = mock(AccountManager.class);
        when(accountManager.isConnected()).thenReturn(false);

        IbkrService ibkrService = mock(IbkrService.class);
        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MarketCalendarService marketCalendarService = mock(MarketCalendarService.class);
        MarketScanner marketScanner = mock(MarketScanner.class);
        when(marketScanner.isSchedulerEnabled()).thenReturn(false);
        when(scannerService.getMaxConcurrentScans()).thenReturn(4);
        when(scannerService.getScannedCount()).thenReturn(0);
        when(scannerService.getCurrentBatchLabel()).thenReturn("batch-1");
        when(scannerService.getTotalToScan()).thenReturn(100);

        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties
        );
    }

    @Test
    @DisplayName("getStatus returns core scanning and account fields")
    void getStatus_includesExpectedKeys() {
        ResponseEntity<Map<String, Object>> response = controller.getStatus();
        assertNotNull(response.getBody());
        Map<String, Object> s = response.getBody();

        assertEquals(false, s.get("isScanning"));
        assertEquals(false, s.get("stopScanRequested"));
        assertEquals(false, s.get("twsConnected"));
        assertEquals(4, s.get("maxConcurrentScans"));
        assertEquals(0, s.get("scannerScanned"));
        assertEquals("batch-1", s.get("scannerBatchLabel"));
        assertEquals(100, s.get("scannerTotal"));
        assertEquals("AUTO", s.get("scannerConcurrentMode"));
        assertEquals("HYBRID", s.get("scannerPrioritizationMode"));
        assertEquals(0.65, s.get("scannerHybridFundamentalWeight"));
        assertEquals(0.35, s.get("scannerHybridMemoryWeight"));
        assertEquals(false, s.get("schedulerEnabled"));
        assertEquals(false, s.get("mockMarketOpen"));
        assertEquals(List.of("SPY", "QQQ"), s.get("hotTickersList"));
        assertEquals("Europe/Madrid", s.get("timezone"));
    }
}
