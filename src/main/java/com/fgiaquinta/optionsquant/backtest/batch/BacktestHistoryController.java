package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for read-only historical backtest run queries.
 *
 * <p>All endpoints are GET — no mutations. Uses {@link BacktestHistoryService}
 * which reads exclusively from the {@code candlesReadDs} pool.
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest/runs")
@RequiredArgsConstructor
public class BacktestHistoryController {

    private final BacktestHistoryService historyService;

    /**
     * List all backtest runs with summary metrics.
     * GET /api/backtest/runs
     */
    @GetMapping
    public ResponseEntity<List<RunSummaryDto>> listRuns() {
        log.debug("[backtest-history] GET /api/backtest/runs");
        return ResponseEntity.ok(historyService.listRuns());
    }

    /**
     * Get detailed statistics for a single run.
     * GET /api/backtest/runs/{runId}/summary
     */
    @GetMapping("/{runId}/summary")
    public ResponseEntity<RunDetailDto> getRunSummary(@PathVariable String runId) {
        log.debug("[backtest-history] GET /api/backtest/runs/{}/summary", runId);
        return ResponseEntity.ok(historyService.getRunSummary(runId));
    }

    /**
     * Get paginated, filterable trades for a run.
     * GET /api/backtest/runs/{runId}/trades?strategy=X&signal_type=Y&win=0&limit=100&offset=0
     */
    @GetMapping("/{runId}/trades")
    public ResponseEntity<TradesPageDto> getTrades(
            @PathVariable String runId,
            @RequestParam(required = false) String strategy,
            @RequestParam(name = "signal_type", required = false) String signalType,
            @RequestParam(required = false) Integer win,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        log.debug("[backtest-history] GET /api/backtest/runs/{}/trades strategy={} signalType={} win={} limit={} offset={}",
                runId, strategy, signalType, win, limit, offset);
        return ResponseEntity.ok(historyService.getTrades(runId, strategy, signalType, win, limit, offset));
    }

    /**
     * Get multi-dimensional loss analysis for a run.
     * GET /api/backtest/runs/{runId}/losses-analysis
     */
    @GetMapping("/{runId}/losses-analysis")
    public ResponseEntity<LossesAnalysisDto> getLossesAnalysis(@PathVariable String runId) {
        log.debug("[backtest-history] GET /api/backtest/runs/{}/losses-analysis", runId);
        return ResponseEntity.ok(historyService.getLossesAnalysis(runId));
    }
}
