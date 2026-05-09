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
import static org.mockito.Mockito.*;

class LiveModeControllerScanActivityTest {

    private LiveModeController controller;
    private StrategyScannerService scannerService;

    @BeforeEach
    void setUp() {
        scannerService = mock(StrategyScannerService.class);
        final IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        final TradingService tradingService = mock(TradingService.class);
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

    @Test
    @DisplayName("getScanActivity idle state → 200 OK with isScanning=false")
    void getScanActivity_idleState_returns200WithIsScanningFalse() {
        when(scannerService.getCurrentBatchLabel()).thenReturn("batch-idle");
        when(scannerService.getScannedCount()).thenReturn(0);
        when(scannerService.getTotalToScan()).thenReturn(0);

        ResponseEntity<Map<String, Object>> res = controller.getScanActivity();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody()).containsEntry("isScanning", false);
    }

    @Test
    @DisplayName("getScanActivity after updateScanningState(true) → isScanning=true")
    void getScanActivity_afterUpdateScanningStateTrue_isScanningIsTrue() {
        when(scannerService.getCurrentBatchLabel()).thenReturn("batch-live");
        when(scannerService.getScannedCount()).thenReturn(1);
        when(scannerService.getTotalToScan()).thenReturn(10);

        controller.updateScanningState(true, "AAPL", 1, 10);
        ResponseEntity<Map<String, Object>> res = controller.getScanActivity();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("isScanning", true);
    }

    @Test
    @DisplayName("getScanActivity → all required fields present in response body")
    void getScanActivity_allRequiredFieldsPresent() {
        when(scannerService.getCurrentBatchLabel()).thenReturn("batch-X");
        when(scannerService.getScannedCount()).thenReturn(3);
        when(scannerService.getTotalToScan()).thenReturn(50);

        ResponseEntity<Map<String, Object>> res = controller.getScanActivity();

        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKeys("activity", "isScanning", "batchLabel", "scanned", "total", "lastScanTime");
    }

    @Test
    @DisplayName("getScanActivity batchLabel → delegates to scannerService.getCurrentBatchLabel()")
    void getScanActivity_batchLabel_delegatesToScannerService() {
        when(scannerService.getCurrentBatchLabel()).thenReturn("MORNING-SCAN-42");
        when(scannerService.getScannedCount()).thenReturn(0);
        when(scannerService.getTotalToScan()).thenReturn(0);

        ResponseEntity<Map<String, Object>> res = controller.getScanActivity();

        assertThat(res.getBody()).containsEntry("batchLabel", "MORNING-SCAN-42");
        verify(scannerService).getCurrentBatchLabel();
    }
}
