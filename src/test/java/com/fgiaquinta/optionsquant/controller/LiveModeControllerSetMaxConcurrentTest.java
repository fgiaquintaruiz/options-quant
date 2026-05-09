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

class LiveModeControllerSetMaxConcurrentTest {

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
    @DisplayName("setMaxConcurrent count=5 → 200 OK, success=true, maxConcurrentScans=5, delegates to scannerService")
    void setMaxConcurrent_typicalValid_returnsOkAndDelegates() {
        ResponseEntity<Map<String, Object>> res = controller.setMaxConcurrent(5);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("maxConcurrentScans", 5);
        verify(scannerService).setMaxConcurrentScans(5);
    }

    @Test
    @DisplayName("setMaxConcurrent count=1 (min boundary) → 200 OK, success=true")
    void setMaxConcurrent_minBoundary_returnsOk() {
        ResponseEntity<Map<String, Object>> res = controller.setMaxConcurrent(1);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        assertThat(res.getBody()).containsEntry("maxConcurrentScans", 1);
        verify(scannerService).setMaxConcurrentScans(1);
    }

    @Test
    @DisplayName("setMaxConcurrent count=16 (max boundary) → 200 OK, success=true")
    void setMaxConcurrent_maxBoundary_returnsOk() {
        ResponseEntity<Map<String, Object>> res = controller.setMaxConcurrent(16);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        assertThat(res.getBody()).containsEntry("maxConcurrentScans", 16);
        verify(scannerService).setMaxConcurrentScans(16);
    }

    @Test
    @DisplayName("setMaxConcurrent count=0 (below range) → 400 Bad Request, success=false, message contains 'between 1 and 16'")
    void setMaxConcurrent_belowRange_returnsBadRequest() {
        ResponseEntity<Map<String, Object>> res = controller.setMaxConcurrent(0);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("success", false);
        assertThat((String) body.get("message")).contains("between 1 and 16");
    }

    @Test
    @DisplayName("setMaxConcurrent count=17 (above range) → 400 Bad Request, success=false")
    void setMaxConcurrent_aboveRange_returnsBadRequest() {
        ResponseEntity<Map<String, Object>> res = controller.setMaxConcurrent(17);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("success", false);
    }

    @Test
    @DisplayName("setMaxConcurrent count=0 → scannerService.setMaxConcurrentScans NEVER called")
    void setMaxConcurrent_invalidCount_serviceNeverCalled() {
        controller.setMaxConcurrent(0);

        verify(scannerService, never()).setMaxConcurrentScans(anyInt());
    }

    @Test
    @DisplayName("setMaxConcurrent valid count → verify(scannerService).setMaxConcurrentScans called exactly once")
    void setMaxConcurrent_validCount_serviceCalledExactlyOnce() {
        controller.setMaxConcurrent(8);

        verify(scannerService, times(1)).setMaxConcurrentScans(8);
    }
}
