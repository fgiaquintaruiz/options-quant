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
import org.mockito.InOrder;

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
    private MarketCalendarService marketCalendar;

    @BeforeEach
    void setUp() {
        strategyScannerService = mock(StrategyScannerService.class);
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);

        OrderExecutionService orderExecutionService = mock(OrderExecutionService.class);
        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);
        TelegramService telegramService = mock(TelegramService.class);
        TrailingStopMonitor trailingStopMonitor = mock(TrailingStopMonitor.class);
        marketCalendar = mock(MarketCalendarService.class);

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

        // Default: market is open — individual tests override when needed
        when(marketCalendar.isRegularMarketHours(any())).thenReturn(true);

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
        InOrder inOrder = inOrder(liveModeController, strategyScannerService);

        marketScanner.scanAndExecute();

        // setScanScores must be called before scanAll
        inOrder.verify(liveModeController).setScanScores(computed);
        inOrder.verify(strategyScannerService).scanAll(anyBoolean(), anyBoolean(), anyBoolean(), anyLong());
    }

    // ─── Market-hours gate applies during replay ────────────────────────────────

    @Test
    @DisplayName("replay active + virtual 08:00 ET (before open) → scanAndExecute returns early")
    void scanAndExecute_returnsEarly_whenReplayActive_andVirtualTimeBeforeMarketOpen() {
        // GIVEN: replay active, virtual time = 08:00 ET = 14:00 CEST (outside regular hours)
        ZonedDateTime eightAmEt = ZonedDateTime.of(2026, 4, 22, 8, 0, 0, 0,
                ZoneId.of("America/New_York"));

        ReplayClock mockClock = mock(ReplayClock.class);
        when(mockClock.isActive()).thenReturn(true);
        when(mockClock.getNow()).thenReturn(eightAmEt);
        ReflectionTestUtils.setField(marketScanner, "replayClock", mockClock);

        // Market calendar says this time is outside regular hours
        when(marketCalendar.isRegularMarketHours(any())).thenReturn(false);

        // WHEN
        marketScanner.scanAndExecute();

        // THEN: scan must NOT proceed — outside market hours even during replay
        verify(strategyScannerService, never()).scanAll(anyBoolean(), anyBoolean(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("replay active + virtual 10:00 ET (market open) → scanAndExecute proceeds")
    void scanAndExecute_proceeds_whenReplayActive_andVirtualTimeDuringMarketHours() {
        // GIVEN: replay active, virtual time = 10:00 ET = 16:00 CEST (inside regular hours)
        ZonedDateTime tenAmEt = ZonedDateTime.of(2026, 4, 22, 10, 0, 0, 0,
                ZoneId.of("America/New_York"));

        ReplayClock mockClock = mock(ReplayClock.class);
        when(mockClock.isActive()).thenReturn(true);
        when(mockClock.getNow()).thenReturn(tenAmEt);
        ReflectionTestUtils.setField(marketScanner, "replayClock", mockClock);

        // Market calendar says this time is inside regular hours
        when(marketCalendar.isRegularMarketHours(any())).thenReturn(true);
        when(scanPrioritizationService.computeScores(anyList())).thenReturn(Map.of());

        // WHEN
        marketScanner.scanAndExecute();

        // THEN: scan proceeds
        verify(strategyScannerService).scanAll(anyBoolean(), anyBoolean(), anyBoolean(), anyLong());
    }
}
