package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.controller.LiveModeController;
import com.fgiaquinta.optionsquant.dto.ScanScoreBreakdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests verifying that {@link MarketScanner#scanAndExecute()} populates
 * the scan scores in {@link LiveModeController} via {@code setScanScores()}
 * before the scan loop runs (Task 3.3 / Task 3.6).
 */
class MarketScannerScanScoresTest {

    private MarketScanner marketScanner;
    private LiveModeController liveModeController;
    private ScanPrioritizationService scanPrioritizationService;
    private StrategyScannerService strategyScannerService;
    private ScannerProperties scannerProperties;

    @BeforeEach
    void setUp() {
        strategyScannerService = mock(StrategyScannerService.class);
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);

        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);
        TelegramService telegramService = mock(TelegramService.class);
        TrailingStopMonitor trailingStopMonitor = mock(TrailingStopMonitor.class);
        MarketCalendarService marketCalendar = mock(MarketCalendarService.class);

        scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        liveModeController = mock(LiveModeController.class);
        scanPrioritizationService = mock(ScanPrioritizationService.class);

        // liveModeController.resolveTickersForLiveScan() returns a known list
        when(liveModeController.resolveTickersForLiveScan()).thenReturn(List.of("NVDA", "MSFT"));
        when(liveModeController.getLiveTickerScope()).thenReturn("HOT");
        when(liveModeController.isStopRequested()).thenReturn(false);
        when(liveModeController.isExtendedHoursEnabled()).thenReturn(false);

        // strategyScannerService.scanAll returns an empty result (scan loop produces nothing)
        StrategyScannerService.ScanResult emptyResult =
                new StrategyScannerService.ScanResult(0, 0, List.of(), 0L, false);
        when(strategyScannerService.scanAll(anyBoolean(), anyBoolean(), anyBoolean(), anyLong()))
                .thenReturn(emptyResult);

        marketScanner = new MarketScanner(
                strategyScannerService, ibkrProperties, orderExecutionService,
                macroFilter, telegramService, trailingStopMonitor,
                liveModeController, marketCalendar, scannerProperties,
                scanPrioritizationService
        );
    }

    // ─── Task 3.3 — MarketScanner.scanAndExecute() populates scores ─────────────

    @Test
    @DisplayName("scanAndExecute invokes setScanScores before scanAll when scheduler path runs")
    void scanAndExecute_setScanScores_called_before_scanAll_in_order() {
        Map<String, ScanScoreBreakdown> computed = Map.of(
                "NVDA", new ScanScoreBreakdown("NVDA", 0.75, 0.60, 0.6825)
        );
        when(scanPrioritizationService.computeScores(anyList())).thenReturn(computed);

        // Inject a mock ReplayClock that reports active=true so scanAndExecute() skips the
        // market-hours gate entirely (replay mode bypasses all time/weekend checks).
        ReplayClock mockClock = mock(ReplayClock.class);
        when(mockClock.isActive()).thenReturn(true);
        when(mockClock.getNow()).thenReturn(
                ZonedDateTime.now(java.time.ZoneId.of("Europe/Madrid"))
        );
        ReflectionTestUtils.setField(marketScanner, "replayClock", mockClock);

        when(liveModeController.isStopRequested()).thenReturn(false);

        // Use org.mockito.InOrder to verify setScanScores is called before scanAll
        var inOrder = inOrder(liveModeController, strategyScannerService);

        marketScanner.scanAndExecute();

        // setScanScores must be called before scanAll
        inOrder.verify(liveModeController).setScanScores(computed);
        inOrder.verify(strategyScannerService).scanAll(anyBoolean(), anyBoolean(), anyBoolean(), anyLong());
    }

    // ─── W3 — market-hours bypass when replay is active ─────────────────────────

    @Test
    @DisplayName("scanAndExecute bypasses market-hours gate when replayClock is active at 3am")
    void scanAndExecute_bypassesMarketHoursGate_whenReplayActive() {
        // GIVEN: replay clock is active and reports 03:00 Spain time (deep outside market hours)
        ZonedDateTime threeAm = ZonedDateTime.now(ZoneId.of("Europe/Madrid"))
                .withHour(3).withMinute(0).withSecond(0).withNano(0);

        ReplayClock mockClock = mock(ReplayClock.class);
        when(mockClock.isActive()).thenReturn(true);
        when(mockClock.getNow()).thenReturn(threeAm);
        ReflectionTestUtils.setField(marketScanner, "replayClock", mockClock);

        when(scanPrioritizationService.computeScores(anyList())).thenReturn(Map.of());

        // WHEN: scanAndExecute() is called
        marketScanner.scanAndExecute();

        // THEN: the scan proceeds — scanAll is invoked, proving the market-hours guard was bypassed
        verify(strategyScannerService).scanAll(anyBoolean(), anyBoolean(), anyBoolean(), anyLong());
    }
}
