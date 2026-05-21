package com.fgiaquinta.optionsquant.options;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.controller.LiveModeController;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Verifies that MarketScanner fires OptionChainRecorderService.snapshotAsync()
 * when processLiveSignalAfterScan() is invoked with a live signal.
 */
class MarketScannerOptionChainHookTest {

    private MarketScanner marketScanner;
    private OptionChainRecorderService optionChainRecorderService;
    private IbkrProperties ibkrProperties;
    private LiveModeController liveModeController;

    private static final TradePlan PLAN = new TradePlan(100.0, 110.0, 95.0, true, LocalTime.of(16, 0));
    private static final ZoneId SPAIN = ZoneId.of("Europe/Madrid");

    @BeforeEach
    void setUp() {
        StrategyScannerService scannerService = mock(StrategyScannerService.class);
        ibkrProperties = mock(IbkrProperties.class);
        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);
        TelegramService telegramService = mock(TelegramService.class);
        TrailingStopMonitor trailingStopMonitor = mock(TrailingStopMonitor.class);
        liveModeController = mock(LiveModeController.class);
        MarketCalendarService marketCalendar = mock(MarketCalendarService.class);
        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        ScanPrioritizationService scanPrioritizationService = mock(ScanPrioritizationService.class);
        optionChainRecorderService = mock(OptionChainRecorderService.class);

        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);
        when(ibkrProperties.autoExecute()).thenReturn(false);
        when(liveModeController.isLiveSignalOlderThanMaxAge(any())).thenReturn(false);
        when(telegramService.generateSecureOrderId(anyString(), anyString(), anyString(), anyDouble()))
                .thenReturn("order-id");

        marketScanner = new MarketScanner(
                scannerService, ibkrProperties, orderExecutionService,
                macroFilter, telegramService, trailingStopMonitor,
                liveModeController, marketCalendar, scannerProperties,
                scanPrioritizationService,
                java.util.Optional.of(optionChainRecorderService),
                java.util.Optional.empty(), java.util.Optional.empty()
        );
    }

    // ─── option chain hook on signal ─────────────────────────────────────────

    @Test
    @DisplayName("processLiveSignalAfterScan calls snapshotAsync with SIGNAL trigger")
    void processLiveSignalAfterScan_callsSnapshotAsync_withSignalTrigger() {
        Signal signal = new Signal(
                "NVDA", "p1_squeeze_put", "PUT", 900.0,
                ZonedDateTime.now(SPAIN), PLAN
        );

        marketScanner.processLiveSignalAfterScan(signal, ZonedDateTime.now(SPAIN));

        ArgumentCaptor<String> tickerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> triggerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> strategyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> directionCaptor = ArgumentCaptor.forClass(String.class);

        verify(optionChainRecorderService, times(1)).snapshotAsync(
                tickerCaptor.capture(),
                anyString(),  // signalId (UUID — any)
                strategyCaptor.capture(),
                directionCaptor.capture(),
                triggerCaptor.capture()
        );

        assertThat(tickerCaptor.getValue()).isEqualTo("NVDA");
        assertThat(strategyCaptor.getValue()).isEqualTo("p1_squeeze_put");
        assertThat(directionCaptor.getValue()).isEqualTo("PUT");
        assertThat(triggerCaptor.getValue()).isEqualTo("SIGNAL");
    }

    @Test
    @DisplayName("processLiveSignalAfterScan passes a non-null UUID as signalId")
    void processLiveSignalAfterScan_passesNonNullUuid() {
        Signal signal = new Signal(
                "TSLA", "c1", "CALL", 300.0,
                ZonedDateTime.now(SPAIN), PLAN
        );

        marketScanner.processLiveSignalAfterScan(signal, ZonedDateTime.now(SPAIN));

        ArgumentCaptor<String> signalIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(optionChainRecorderService).snapshotAsync(
                anyString(),
                signalIdCaptor.capture(),
                anyString(), anyString(), anyString()
        );

        assertThat(signalIdCaptor.getValue()).isNotNull().isNotBlank();
        // UUID format: 8-4-4-4-12
        assertThat(signalIdCaptor.getValue()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("processLiveSignalAfterScan fires snapshot even when autoExecute is disabled")
    void processLiveSignalAfterScan_firesSnapshot_whenAutoExecuteDisabled() {
        when(ibkrProperties.autoExecute()).thenReturn(false);

        Signal signal = new Signal(
                "AMD", "p2", "PUT", 120.0,
                ZonedDateTime.now(SPAIN), PLAN
        );

        marketScanner.processLiveSignalAfterScan(signal, ZonedDateTime.now(SPAIN));

        // Snapshot must fire regardless of autoExecute setting
        verify(optionChainRecorderService, times(1))
                .snapshotAsync(eq("AMD"), anyString(), eq("p2"), eq("PUT"), eq("SIGNAL"));
    }

    @Test
    @DisplayName("processLiveSignalAfterScan still fires snapshot when tradePlan is null")
    void processLiveSignalAfterScan_firesSnapshot_whenTradePlanIsNull() {
        Signal signal = new Signal(
                "SPY", "momentum", "CALL", 500.0,
                ZonedDateTime.now(SPAIN), null
        );

        marketScanner.processLiveSignalAfterScan(signal, ZonedDateTime.now(SPAIN));

        // Option chain recording should be decoupled from trade plan availability
        verify(optionChainRecorderService, times(1))
                .snapshotAsync(eq("SPY"), anyString(), eq("momentum"), eq("CALL"), eq("SIGNAL"));
    }
}
