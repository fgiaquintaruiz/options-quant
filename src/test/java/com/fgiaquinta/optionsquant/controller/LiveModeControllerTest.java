package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.domain.NewsBias;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that GET /live-ui/signals exposes newsBias and earningsAlert fields.
 */
class LiveModeControllerTest {

    private LiveModeController controller;

    @BeforeEach
    void setUp() {
        IbkrService ibkrService = mock(IbkrService.class);
        when(ibkrService.isConnected()).thenReturn(true);
        AccountManager accountManager = mock(AccountManager.class);
        when(accountManager.isConnected()).thenReturn(true);

        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);

        controller = new LiveModeController(
                mock(StrategyScannerService.class), mock(IbkrProperties.class),
                mock(TradingService.class), mock(TickerService.class),
                accountManager, ibkrService, mock(OrderExecutionService.class),
                mock(MarketCalendarService.class), mock(MarketScanner.class),
                scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );
    }

    @Test
    @DisplayName("GET /signals includes newsBias=CALL and earningsAlert=true when signal has those values")
    void shouldIncludeNewsBiasAndEarningsAlertInSignalsResponse() {
        // GIVEN — a signal with newsBias=CALL and earningsAlert=true
        Signal signal = new Signal(
                "NVDA", "squeeze_strat", "CALL", 900.0,
                ZonedDateTime.now(), null, "hammer", false,
                NewsBias.CALL, true
        );
        controller.addLiveSignal(signal);

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getSignals();

        // THEN
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) response.getBody().get("signals");
        assertThat(signals).hasSize(1);
        Map<String, Object> row = signals.get(0);
        assertThat(row).containsEntry("newsBias", "CALL");
        assertThat(row).containsEntry("earningsAlert", true);
    }

    @Test
    @DisplayName("GET /signals returns newsBias=NEUTRAL when signal has newsBias=null (via NEUTRAL default)")
    void shouldReturnNeutralWhenNewsBiasIsNeutral() {
        // GIVEN — a signal using the backward-compat constructor (newsBias defaults to NEUTRAL)
        Signal signal = new Signal(
                "AAPL", "trend_strat", "PUT", 180.0,
                ZonedDateTime.now(), null
        );
        controller.addLiveSignal(signal);

        // WHEN
        ResponseEntity<Map<String, Object>> response = controller.getSignals();

        // THEN
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals = (List<Map<String, Object>>) response.getBody().get("signals");
        assertThat(signals).hasSize(1);
        Map<String, Object> row = signals.get(0);
        assertThat(row).containsEntry("newsBias", "NEUTRAL");
        assertThat(row).containsEntry("earningsAlert", false);
    }
}
