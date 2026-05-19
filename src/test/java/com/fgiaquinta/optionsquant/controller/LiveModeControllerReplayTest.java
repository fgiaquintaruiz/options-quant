package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
    @DisplayName("POST /replay/start returns 409 with sanitized error and errorId when ReplayService rejects")
    void startReplay_returnsConflictOnRejection() {
        doThrow(new ReplayService.ReplayRejectedException("market-open: internal-host:5432"))
                .when(replayService).start(any(LocalDate.class), anyInt());

        ResponseEntity<Map<String, Object>> response = controller.startReplay("2026-04-22", 60);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).containsEntry("success", false);
        // Lock the new sanitized contract — exception message is NOT leaked.
        assertThat(response.getBody()).containsEntry("error", "Replay start failed — see logs.");
        assertThat((String) response.getBody().get("error")).doesNotContain("market-open");
        assertThat((String) response.getBody().get("error")).doesNotContain("internal-host");
        // errorId correlates the response with the server-side log.
        assertThat(response.getBody()).containsKey("errorId");
        assertThat((String) response.getBody().get("errorId")).isNotBlank();
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

    // ─── getSignals source-selection tests ──────────────────────────────────

    @Test
    @DisplayName("GET /signals during replay returns replaySignals, not liveSignals")
    void getSignals_whenReplayActive_returnsReplaySignals() {
        Signal replaySig = new Signal("AAPL", "SMA", "BUY", 180.0,
                ZonedDateTime.now(), null, "none", true);
        Signal liveSig   = new Signal("MSFT", "EMA", "SELL", 310.0,
                ZonedDateTime.now(), null, "none", false);

        CopyOnWriteArrayList<Signal> replayList = new CopyOnWriteArrayList<>(List.of(replaySig));
        CopyOnWriteArrayList<Signal> liveList   = new CopyOnWriteArrayList<>(List.of(liveSig));
        ReflectionTestUtils.setField(controller, "replaySignals", replayList);
        ReflectionTestUtils.setField(controller, "liveSignals", liveList);

        replayClock.activate(ZonedDateTime.parse("2026-04-22T14:30:00Z"), 60, "R-test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals =
                (List<Map<String, Object>>) controller.getSignals().getBody().get("signals");

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).containsEntry("ticker", "AAPL");
    }

    @Test
    @DisplayName("GET /signals when replay inactive returns liveSignals")
    void getSignals_whenReplayInactive_returnsLiveSignals() {
        Signal liveSig = new Signal("MSFT", "EMA", "SELL", 310.0,
                ZonedDateTime.now(), null, "none", false);

        CopyOnWriteArrayList<Signal> liveList = new CopyOnWriteArrayList<>(List.of(liveSig));
        ReflectionTestUtils.setField(controller, "liveSignals", liveList);
        ReflectionTestUtils.setField(controller, "replaySignals", new CopyOnWriteArrayList<>());

        // replayClock is inactive by default

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals =
                (List<Map<String, Object>>) controller.getSignals().getBody().get("signals");

        assertThat(signals).hasSize(1);
        assertThat(signals.get(0)).containsEntry("ticker", "MSFT");
    }

    @Test
    @DisplayName("replaySignals bucket: adding 501 signals drops the oldest, keeps size at 500")
    void replaySignals_capAt500_dropsOldest() {
        // Wire a real replayClock so addLiveSignal routes to the replaySignals path
        replayClock.activate(ZonedDateTime.parse("2026-04-22T14:30:00Z"), 60, "R-cap-test");
        ReflectionTestUtils.setField(controller, "replayClock", replayClock);

        // Build 501 distinct replay signals — replay=true routes to replaySignals bucket
        for (int i = 1; i <= 501; i++) {
            Signal s = new Signal(
                    "T" + String.format("%03d", i), "SMA", "BUY", 100.0 + i,
                    ZonedDateTime.parse("2026-04-22T14:30:00Z"), null, "none", true
            );
            controller.addLiveSignal(s);
        }

        @SuppressWarnings("unchecked")
        CopyOnWriteArrayList<Signal> bucket =
                (CopyOnWriteArrayList<Signal>) ReflectionTestUtils.getField(controller, "replaySignals");

        // Size must be capped at 500
        assertThat(bucket).hasSize(500);
        // The oldest signal (T001) must have been dropped; index 0 is now T002
        assertThat(bucket.get(0).ticker()).isEqualTo("T002");
        // The newest signal (T501) must still be present at the end
        assertThat(bucket.get(499).ticker()).isEqualTo("T501");
    }

    @Test
    @DisplayName("GET /signals during replay does not leak any liveSignals item")
    void getSignals_whenReplayActive_doesNotLeakLiveSignals() {
        Signal replaySig = new Signal("AAPL", "SMA", "BUY", 180.0,
                ZonedDateTime.now(), null, "none", true);
        Signal liveSig   = new Signal("MSFT", "EMA", "SELL", 310.0,
                ZonedDateTime.now(), null, "none", false);

        CopyOnWriteArrayList<Signal> replayList = new CopyOnWriteArrayList<>(List.of(replaySig));
        CopyOnWriteArrayList<Signal> liveList   = new CopyOnWriteArrayList<>(List.of(liveSig));
        ReflectionTestUtils.setField(controller, "replaySignals", replayList);
        ReflectionTestUtils.setField(controller, "liveSignals", liveList);

        replayClock.activate(ZonedDateTime.parse("2026-04-22T14:30:00Z"), 60, "R-leak-test");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> signals =
                (List<Map<String, Object>>) controller.getSignals().getBody().get("signals");

        assertThat(signals).noneMatch(m -> "MSFT".equals(m.get("ticker")));
    }
}
