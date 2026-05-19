package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.service.*;
import com.fgiaquinta.optionsquant.service.StrategyScannerService.Signal;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the on-complete hook that emits a replay summary when the replay stops.
 *
 * The summary must include:
 * - total signals emitted
 * - count of unique tickers
 * - date range (earliest and latest signal timestamp)
 *
 * Sub-task B of the replay serialization enhancement.
 */
class LiveModeControllerReplaySummaryTest {

    @TempDir
    Path tempDir;

    private LiveModeController controller;
    private ReplayService replayService;

    @BeforeEach
    void setUp() {
        IbkrProperties ibkrProperties = mock(IbkrProperties.class);
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

        controller = new LiveModeController(
                mock(StrategyScannerService.class), ibkrProperties, mock(TradingService.class),
                mock(TickerService.class), mock(AccountManager.class), mock(IbkrService.class),
                mock(OrderExecutionService.class), mock(MarketCalendarService.class),
                mock(MarketScanner.class), scannerProperties, mock(MacroEnvironmentFilter.class),
                mock(ScanPrioritizationService.class)
        );

        replayService = mock(ReplayService.class);
        ReflectionTestUtils.setField(controller, "replayService", replayService);
        ReflectionTestUtils.setField(controller, "replaySignalDir", tempDir.toString());
    }

    private Signal replaySignal(String ticker, ZonedDateTime ts) {
        TradePlan plan = new TradePlan(100.0, 110.0, 95.0, true, LocalTime.of(15, 45));
        return new Signal(ticker, "C1Squeeze", "CALL", 100.0, ts, plan, "hammer", true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // computeReplaySummary — unit-level tests on the pure helper
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("computeReplaySummary returns correct signal count")
    void computeReplaySummary_returnsCorrectSignalCount() {
        ZonedDateTime t1 = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        ZonedDateTime t2 = ZonedDateTime.parse("2026-04-22T14:50:00Z");
        controller.addLiveSignal(replaySignal("NVDA", t1));
        controller.addLiveSignal(replaySignal("AAPL", t2));

        LiveModeController.ReplaySummary summary = controller.computeReplaySummary();

        assertThat(summary.totalSignals()).isEqualTo(2);
    }

    @Test
    @DisplayName("computeReplaySummary counts unique tickers (not total signals)")
    void computeReplaySummary_countsUniqueTickers() {
        ZonedDateTime t1 = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        ZonedDateTime t2 = ZonedDateTime.parse("2026-04-22T14:50:00Z");
        ZonedDateTime t3 = ZonedDateTime.parse("2026-04-22T15:05:00Z");
        // NVDA emitted twice, AAPL once → 2 unique tickers
        controller.addLiveSignal(replaySignal("NVDA", t1));
        controller.addLiveSignal(replaySignal("AAPL", t2));
        controller.addLiveSignal(replaySignal("NVDA", t3));

        LiveModeController.ReplaySummary summary = controller.computeReplaySummary();

        assertThat(summary.uniqueTickers()).isEqualTo(2);
    }

    @Test
    @DisplayName("computeReplaySummary captures earliest and latest signal timestamps")
    void computeReplaySummary_capturesDateRange() {
        ZonedDateTime earliest = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        ZonedDateTime middle   = ZonedDateTime.parse("2026-04-22T14:50:00Z");
        ZonedDateTime latest   = ZonedDateTime.parse("2026-04-22T15:05:00Z");
        controller.addLiveSignal(replaySignal("NVDA", middle));
        controller.addLiveSignal(replaySignal("AAPL", earliest));
        controller.addLiveSignal(replaySignal("TSLA", latest));

        LiveModeController.ReplaySummary summary = controller.computeReplaySummary();

        assertThat(summary.firstSignalAt()).isEqualTo(earliest);
        assertThat(summary.lastSignalAt()).isEqualTo(latest);
    }

    @Test
    @DisplayName("computeReplaySummary returns empty summary when no replay signals exist")
    void computeReplaySummary_emptyWhenNoSignals() {
        LiveModeController.ReplaySummary summary = controller.computeReplaySummary();

        assertThat(summary.totalSignals()).isZero();
        assertThat(summary.uniqueTickers()).isZero();
        assertThat(summary.firstSignalAt()).isNull();
        assertThat(summary.lastSignalAt()).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // stopReplay endpoint — verifies summary is included in the response
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /replay/stop response includes replay summary fields")
    void stopReplay_responseIncludesSummaryFields() {
        ZonedDateTime t1 = ZonedDateTime.parse("2026-04-22T14:35:00Z");
        ZonedDateTime t2 = ZonedDateTime.parse("2026-04-22T14:50:00Z");
        controller.addLiveSignal(replaySignal("NVDA", t1));
        controller.addLiveSignal(replaySignal("AAPL", t2));

        ResponseEntity<Map<String, Object>> response = controller.stopReplay();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsKey("summary");

        @SuppressWarnings("unchecked")
        Map<String, Object> summaryMap = (Map<String, Object>) body.get("summary");
        assertThat(summaryMap).containsKey("totalSignals");
        assertThat(summaryMap).containsKey("uniqueTickers");
        assertThat(summaryMap).containsKey("firstSignalAt");
        assertThat(summaryMap).containsKey("lastSignalAt");
        assertThat(summaryMap.get("totalSignals")).isEqualTo(2);
        assertThat(summaryMap.get("uniqueTickers")).isEqualTo(2);
    }

    @Test
    @DisplayName("POST /replay/stop includes empty summary when no signals were emitted")
    void stopReplay_emptySummaryWhenNoSignals() {
        ResponseEntity<Map<String, Object>> response = controller.stopReplay();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> summaryMap = (Map<String, Object>) response.getBody().get("summary");
        assertThat(summaryMap.get("totalSignals")).isEqualTo(0);
        assertThat(summaryMap.get("uniqueTickers")).isEqualTo(0);
    }
}
