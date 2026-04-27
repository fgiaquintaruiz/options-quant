package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HTTP contract for live-replay-mode endpoints and PAPER/LIVE chip.
 * Covers success + rejection paths without spinning up Spring.
 */
class LiveModeControllerReplayTest {

    private LiveModeController controller;
    private ReplayService replayService;
    private ReplayClock replayClock;
    private IbkrProperties ibkrProperties;

    @BeforeEach
    void setUp() {
        ibkrProperties = mock(IbkrProperties.class);
        when(ibkrProperties.autoExecute()).thenReturn(false);
        when(ibkrProperties.riskPerTradePct()).thenReturn(0.02);
        when(ibkrProperties.hotTickers()).thenReturn(List.of("SPY"));
        when(ibkrProperties.accountId()).thenReturn("DUN598126");

        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);

        controller = new LiveModeController(
                mock(StrategyScannerService.class), ibkrProperties, mock(TradingService.class),
                mock(TickerService.class), mock(AccountManager.class), mock(IbkrService.class),
                mock(OrderExecutionService.class), mock(MarketCalendarService.class),
                mock(MarketScanner.class), scannerProperties, macroFilter,
                mock(ScanPrioritizationService.class)
        );

        replayService = mock(ReplayService.class);
        replayClock = new ReplayClock();
        ReflectionTestUtils.setField(controller, "replayService", replayService);
        ReflectionTestUtils.setField(controller, "replayClock", replayClock);
    }

    @Test
    @DisplayName("GET /account-mode returns PAPER when isPaperAccount=true")
    void getAccountMode_returnsPaperForDuPrefix() {
        when(ibkrProperties.accountId()).thenReturn("DUN598126");
        when(ibkrProperties.isPaperAccount()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.getAccountMode();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("mode", "PAPER");
        assertThat(response.getBody()).containsEntry("accountId", "DUN598126");
    }

    @Test
    @DisplayName("GET /account-mode returns LIVE when isPaperAccount=false")
    void getAccountMode_returnsLiveForNonDuPrefix() {
        when(ibkrProperties.accountId()).thenReturn("U1234567");
        when(ibkrProperties.isPaperAccount()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.getAccountMode();

        assertThat(response.getBody()).containsEntry("mode", "LIVE");
    }

    @Test
    @DisplayName("POST /replay/start returns 409 when ReplayService rejects")
    void startReplay_returnsConflictOnRejection() {
        doThrow(new ReplayService.ReplayRejectedException("market-open"))
                .when(replayService).start(any(LocalDate.class), anyInt());

        ResponseEntity<Map<String, Object>> response = controller.startReplay("2026-04-22", 60);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("success", false);
        assertThat(response.getBody()).containsEntry("error", "market-open");
    }

    @Test
    @DisplayName("POST /replay/start returns 400 on invalid date")
    void startReplay_returnsBadRequestOnInvalidDate() {
        ResponseEntity<Map<String, Object>> response = controller.startReplay("not-a-date", 60);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("success", false);
    }

    @Test
    @DisplayName("POST /replay/start returns 200 + runId on success")
    void startReplay_returnsOkWithRunId() {
        java.time.ZonedDateTime virtualOpen =
                java.time.ZonedDateTime.parse("2026-04-22T14:30:00Z");
        when(replayService.start(any(LocalDate.class), anyInt()))
                .thenReturn(new ReplayService.StartResult("R-abc12345", virtualOpen, 60));

        ResponseEntity<Map<String, Object>> response = controller.startReplay("2026-04-22", 60);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("success", true);
        assertThat(response.getBody()).containsEntry("runId", "R-abc12345");
        assertThat(response.getBody()).containsEntry("speed", 60);
    }

    @Test
    @DisplayName("GET /replay/status returns inactive state by default")
    void getReplayStatus_returnsInactiveWhenNotStarted() {
        ResponseEntity<Map<String, Object>> response = controller.getReplayStatus();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("active", false);
    }

    @Test
    @DisplayName("GET /replay/status reflects active clock")
    void getReplayStatus_reflectsActiveClock() {
        replayClock.activate(
                java.time.ZonedDateTime.parse("2026-04-22T14:30:00Z"), 60, "R-test01");

        ResponseEntity<Map<String, Object>> response = controller.getReplayStatus();

        assertThat(response.getBody()).containsEntry("active", true);
        assertThat(response.getBody()).containsEntry("speed", 60);
        assertThat(response.getBody()).containsEntry("runId", "R-test01");
    }
}
