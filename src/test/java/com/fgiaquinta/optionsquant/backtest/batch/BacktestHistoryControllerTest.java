package com.fgiaquinta.optionsquant.backtest.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fgiaquinta.optionsquant.backtest.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BacktestHistoryController.
 * No Spring context — direct instantiation with mocks.
 */
class BacktestHistoryControllerTest {

    private BacktestHistoryService service;
    private BacktestHistoryController controller;

    @BeforeEach
    void setUp() {
        service = mock(BacktestHistoryService.class);
        controller = new BacktestHistoryController(service);
    }

    // -------------------------------------------------------------------------
    // T1 — GET /api/backtest/runs — returns 200 with JSON list
    // -------------------------------------------------------------------------

    @Test
    void controller_listRuns_returns200WithList() {
        List<RunSummaryDto> runs = List.of(
                new RunSummaryDto("2026-02-01_run", 3, 3, 15L, 42.5),
                new RunSummaryDto("2026-01-01_run", 2, 2, 8L, -5.0)
        );
        when(service.listRuns()).thenReturn(runs);

        ResponseEntity<List<RunSummaryDto>> response = controller.listRuns();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).runId()).isEqualTo("2026-02-01_run");
    }

    // -------------------------------------------------------------------------
    // T2 — GET /api/backtest/runs/{runId}/summary — returns 200 with RunDetailDto
    // -------------------------------------------------------------------------

    @Test
    void controller_getRunSummary_returns200WithJson() {
        String runId = "2026-02-01_run";
        RunDetailDto detail = new RunDetailDto(
                runId, 15L, 42.5, 73.3,
                List.of(new StrategyBreakdownDto("MOMENTUM", 10L, 80.0, 35.0, 5.0, -3.0)),
                List.of(new SignalTypeBreakdownDto("CALL", 8L, 87.5, 30.0))
        );
        when(service.getRunSummary(runId)).thenReturn(detail);

        ResponseEntity<RunDetailDto> response = controller.getRunSummary(runId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        RunDetailDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.runId()).isEqualTo(runId);
        assertThat(body.totalTrades()).isEqualTo(15L);
        assertThat(body.byStrategy()).hasSize(1);
        assertThat(body.bySignalType()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // T3 — GET /api/backtest/runs/{runId}/trades — returns 200 with TradesPageDto
    // -------------------------------------------------------------------------

    @Test
    void controller_getTrades_returns200WithPageDto() {
        String runId = "2026-02-01_run";
        TradesPageDto page = new TradesPageDto(
                List.of(new TradeRecordDto(1L, runId, "AAPL", "MOMENTUM", "MIN_15",
                        "2026-02-01T10:00:00Z", "CALL", 100.0, 105.0, 5.0, 1, null)),
                25L
        );
        when(service.getTrades(eq(runId), isNull(), isNull(), isNull(), eq(100), eq(0)))
                .thenReturn(page);

        ResponseEntity<TradesPageDto> response = controller.getTrades(
                runId, null, null, null, 100, 0);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().total()).isEqualTo(25L);
        assertThat(response.getBody().trades()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // T4 — GET /api/backtest/runs/{runId}/trades with win filter — passes filter to service
    // -------------------------------------------------------------------------

    @Test
    void controller_getTrades_withWinFilter_delegatesToService() {
        String runId = "2026-02-01_run";
        when(service.getTrades(runId, null, null, 0, 50, 10))
                .thenReturn(new TradesPageDto(List.of(), 0L));

        ResponseEntity<TradesPageDto> response = controller.getTrades(runId, null, null, 0, 50, 10);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).getTrades(runId, null, null, 0, 50, 10);
    }

    // -------------------------------------------------------------------------
    // T5 — GET /api/backtest/runs/{runId}/losses-analysis — returns 200 with analysis
    // -------------------------------------------------------------------------

    @Test
    void controller_getLossesAnalysis_returns200WithAnalysis() {
        String runId = "2026-02-01_run";
        LossesAnalysisDto analysis = new LossesAnalysisDto(
                List.of(new HourlyLossDto("09", 5L, -4.2)),
                List.of(new PatternLossDto("DOJI", 3L, -3.5)),
                List.of(new TickerLossDto("AAPL", 2L, -8.0)),
                List.of()
        );
        when(service.getLossesAnalysis(runId)).thenReturn(analysis);

        ResponseEntity<LossesAnalysisDto> response = controller.getLossesAnalysis(runId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        LossesAnalysisDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.byHour()).hasSize(1);
        assertThat(body.byPattern()).hasSize(1);
        assertThat(body.byTicker()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // T6 — listRuns returns empty list (no 500) when service returns empty
    // -------------------------------------------------------------------------

    @Test
    void controller_listRuns_whenServiceReturnsEmpty_returns200WithEmptyList() {
        when(service.listRuns()).thenReturn(List.of());

        ResponseEntity<List<RunSummaryDto>> response = controller.listRuns();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }
}
