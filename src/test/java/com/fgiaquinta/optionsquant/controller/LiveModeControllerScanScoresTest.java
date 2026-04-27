package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.config.ScannerProperties;
import com.fgiaquinta.optionsquant.dto.ScanScoreBreakdown;
import com.fgiaquinta.optionsquant.dto.ScanScoresResponse;
import com.fgiaquinta.optionsquant.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code GET /live-ui/scan-scores} endpoint.
 *
 * Tasks 3.1 (empty before scan) and 3.2 (populated via setScanScores).
 * W1 (envelope shape): response is {@link ScanScoresResponse} with
 * {@code scoresByTicker} + {@code scanStartedAt} fields.
 */
class LiveModeControllerScanScoresTest {

    private LiveModeController controller;
    private ScanPrioritizationService scanPrioritizationService;

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
        when(scannerService.getTotalToScan()).thenReturn(0);

        ScannerProperties scannerProperties = mock(ScannerProperties.class);
        when(scannerProperties.concurrentMode()).thenReturn(ScannerProperties.ConcurrentMode.AUTO);
        when(scannerProperties.prioritizationMode()).thenReturn(ScannerProperties.PrioritizationMode.HYBRID);
        when(scannerProperties.hybridFundamentalWeight()).thenReturn(0.65);
        when(scannerProperties.hybridMemoryWeight()).thenReturn(0.35);
        when(scannerProperties.exclusiveScanSchedulerLockWaitMs()).thenReturn(5000L);
        when(scannerProperties.livePreemptWaitMs()).thenReturn(60_000L);

        MacroEnvironmentFilter macroFilter = mock(MacroEnvironmentFilter.class);
        when(macroFilter.getRegime()).thenReturn(MacroEnvironmentFilter.MarketRegime.NEUTRAL);
        when(macroFilter.getMomentum()).thenReturn(MacroEnvironmentFilter.ShortTermMomentum.FLAT);

        scanPrioritizationService = mock(ScanPrioritizationService.class);

        controller = new LiveModeController(
                scannerService, ibkrProperties, tradingService, tickerService,
                accountManager, ibkrService, orderExecutionService,
                marketCalendarService, marketScanner, scannerProperties, macroFilter,
                scanPrioritizationService
        );
    }

    // ─── W1: envelope shape — before first scan ────────────────────────────────

    @Test
    @DisplayName("GET /scan-scores returns envelope with empty scoresByTicker and null scanStartedAt before first scan")
    void scanScores_returns_envelope_shape_before_first_scan() {
        ResponseEntity<ScanScoresResponse> response = controller.getScanScores();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().scoresByTicker()).isEmpty();
        assertThat(response.getBody().scanStartedAt()).isNull();
    }

    // ─── W1: envelope shape — scanStartedAt populated after setScanScores ──────

    @Test
    @DisplayName("GET /scan-scores envelope includes non-null scanStartedAt after setScanScores is called")
    void scanScores_envelope_includes_scanStartedAt_after_setScanScores() {
        Map<String, ScanScoreBreakdown> known = Map.of(
                "NVDA", new ScanScoreBreakdown("NVDA", 0.75, 0.60, 0.6825)
        );

        controller.setScanScores(known);

        ResponseEntity<ScanScoresResponse> response = controller.getScanScores();

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().scanStartedAt()).isNotNull();
        // ISO-8601 instant: must be parseable
        assertThat(response.getBody().scanStartedAt().toString()).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
    }

    // ─── Task 3.1 — empty scoresByTicker before first scan ─────────────────────

    @Test
    @DisplayName("GET /scan-scores scoresByTicker is empty before any scan")
    void scanScores_scoresByTicker_empty_before_first_scan() {
        ResponseEntity<ScanScoresResponse> response = controller.getScanScores();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().scoresByTicker()).isEmpty();
    }

    // ─── Task 3.2 — scoresByTicker populated after setScanScores ───────────────

    @Test
    @DisplayName("GET /scan-scores scoresByTicker is populated after setScanScores is called")
    void scanScores_scoresByTicker_populated_after_setScanScores() {
        Map<String, ScanScoreBreakdown> known = Map.of(
                "NVDA", new ScanScoreBreakdown("NVDA", 0.75, 0.60, 0.6825),
                "MSFT", new ScanScoreBreakdown("MSFT", 0.80, 0.55, 0.7125)
        );
        when(scanPrioritizationService.computeScores(anyList())).thenReturn(known);

        controller.setScanScores(known);

        ResponseEntity<ScanScoresResponse> response = controller.getScanScores();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().scoresByTicker()).containsKey("NVDA");
        assertThat(response.getBody().scoresByTicker()).containsKey("MSFT");
        assertThat(response.getBody().scoresByTicker().get("NVDA").hybridScore()).isEqualTo(0.6825);
        assertThat(response.getBody().scoresByTicker().get("MSFT").fundamentalScore()).isEqualTo(0.80);
    }
}
