package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import com.fgiaquinta.optionsquant.backtest.grid.GridAxis;
import com.fgiaquinta.optionsquant.backtest.grid.GridCellResult;
import com.fgiaquinta.optionsquant.backtest.grid.GridOptimizationSummary;
import com.fgiaquinta.optionsquant.backtest.grid.GridSearchResult;
import com.fgiaquinta.optionsquant.backtest.grid.GridSearchService;
import com.fgiaquinta.optionsquant.backtest.grid.PromoteRiskService;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import com.fgiaquinta.optionsquant.service.TickerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for grid search / promote with {@link GridSearchService} fully mocked (no TWS, no backtest engine).
 */
@WebMvcTest(controllers = BacktestDashboardController.class)
class BacktestGridSearchWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BacktestEngine backtestEngine;
    @MockitoBean
    private TickerService tickerService;
    @MockitoBean
    private TickerMemory tickerMemory;
    @MockitoBean
    private GridSearchService gridSearchService;
    @MockitoBean
    private PromoteRiskService promoteRiskService;
    @MockitoBean
    private MetricsService metricsService;

    @Test
    @DisplayName("POST grid-search returns mocked JSON and 200")
    void gridSearchOk() throws Exception {
        GridOptimizationSummary opt = new GridOptimizationSummary(
                true, 0, Map.of("tpMultiplierDelta", 0.0, "slMultiplierDelta", 0.0), 100.0, "best");
        GridSearchResult result = new GridSearchResult(
                true, "ok", 1, 1,
                List.of(new GridCellResult(0, Map.of("tpMultiplierDelta", 0.0, "slMultiplierDelta", 0.0),
                        10, 0.01, 5, 0.5, 0.1, 1.2, 1L)),
                "backtest/grid-results/x.csv", opt, null, null);
        when(gridSearchService.run(any())).thenReturn(result);

        String body = """
                {
                  "tickers": ["SPY"],
                  "fromDate": "2025-01-01",
                  "toDate": "2025-02-01",
                  "initialCapital": 50000,
                  "riskPerTradePct": 0.02,
                  "slippagePct": 0.005,
                  "commissionPerContract": 0.65,
                  "maxConcurrentTrades": 3,
                  "executionTimeframe": "MIN_15",
                  "includeTradePlans": true,
                  "deterministicMode": false,
                  "axes": [
                    { "name": "tpMultiplierDelta", "values": [0.0] },
                    { "name": "slMultiplierDelta", "values": [0.0] }
                  ],
                  "searchMode": "EXHAUSTIVE",
                  "randomSampleCount": null,
                  "randomSeed": null,
                  "constraintMinTrades": null,
                  "constraintMaxDrawdownPct": null,
                  "primaryMetric": "TOTAL_PNL",
                  "walkForwardTrainDays": null,
                  "walkForwardTestDays": null,
                  "walkForwardStepDays": null
                }
                """;

        mockMvc.perform(post("/backtest-ui/grid-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.optimization.hasWinner").value(true));

        verify(gridSearchService).run(any());
    }

    @Test
    @DisplayName("POST grid-search returns 400 when service rejects request")
    void gridSearchBadRequest() throws Exception {
        when(gridSearchService.run(any())).thenThrow(new IllegalArgumentException("tickers must not be empty"));

        mockMvc.perform(post("/backtest-ui/grid-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tickers\":[],\"fromDate\":\"2025-01-01\",\"toDate\":\"2025-02-01\","
                                + "\"initialCapital\":50000,\"riskPerTradePct\":0.02,\"slippagePct\":0.005,"
                                + "\"commissionPerContract\":0.65,\"maxConcurrentTrades\":3,"
                                + "\"executionTimeframe\":\"MIN_15\",\"includeTradePlans\":true,"
                                + "\"deterministicMode\":false,\"axes\":[{\"name\":\"tpMultiplierDelta\",\"values\":[0.0]},"
                                + "{\"name\":\"slMultiplierDelta\",\"values\":[0.0]}],"
                                + "\"searchMode\":\"EXHAUSTIVE\",\"randomSampleCount\":null,\"randomSeed\":null,"
                                + "\"constraintMinTrades\":null,\"constraintMaxDrawdownPct\":null,"
                                + "\"primaryMetric\":\"TOTAL_PNL\",\"walkForwardTrainDays\":null,"
                                + "\"walkForwardTestDays\":null,\"walkForwardStepDays\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("tickers must not be empty"));
    }

    @Test
    @DisplayName("POST grid-search returns walk-forward payload from mock")
    void gridSearchWalkForwardShape() throws Exception {
        var fold = new com.fgiaquinta.optionsquant.backtest.grid.WalkForwardFoldResult(
                0,
                java.time.LocalDate.of(2025, 1, 1),
                java.time.LocalDate.of(2025, 1, 30),
                java.time.LocalDate.of(2025, 1, 31),
                java.time.LocalDate.of(2025, 2, 13),
                true,
                Map.of("tpMultiplierDelta", 0.1, "slMultiplierDelta", 0.0),
                50.0,
                "ok",
                4,
                4,
                12.0,
                2,
                0.55,
                0.08,
                1.1,
                33L,
                null);
        var summary = new com.fgiaquinta.optionsquant.backtest.grid.WalkForwardOosSummary(1, 1, 1, 12.0, 2, "TOTAL_PNL", "detail");
        GridSearchResult result = new GridSearchResult(
                true, "walk-forward ok", 5, 5, List.of(), "backtest/grid-results/wf.csv", null,
                List.of(fold), summary);
        when(gridSearchService.run(any())).thenReturn(result);

        mockMvc.perform(post("/backtest-ui/grid-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tickers": ["SPY"],
                                  "fromDate": "2025-01-01",
                                  "toDate": "2025-06-01",
                                  "initialCapital": 50000,
                                  "riskPerTradePct": 0.02,
                                  "slippagePct": 0.005,
                                  "commissionPerContract": 0.65,
                                  "maxConcurrentTrades": 3,
                                  "executionTimeframe": "MIN_15",
                                  "includeTradePlans": true,
                                  "deterministicMode": false,
                                  "axes": [
                                    { "name": "tpMultiplierDelta", "values": [0.0] },
                                    { "name": "slMultiplierDelta", "values": [0.0] }
                                  ],
                                  "searchMode": "EXHAUSTIVE",
                                  "randomSampleCount": null,
                                  "randomSeed": null,
                                  "constraintMinTrades": null,
                                  "constraintMaxDrawdownPct": null,
                                  "primaryMetric": "TOTAL_PNL",
                                  "walkForwardTrainDays": 30,
                                  "walkForwardTestDays": 14,
                                  "walkForwardStepDays": 14
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walkForwardSummary.foldsWithOosBacktest").value(1))
                .andExpect(jsonPath("$.walkForwardFolds[0].foldIndex").value(0));
    }

    @Test
    @DisplayName("POST promote-risk-params returns 400 when service throws IllegalArgumentException")
    void promoteBadRequestWhenServiceThrows() throws Exception {
        when(promoteRiskService.promote(any())).thenThrow(new IllegalArgumentException("ticker is required"));

        mockMvc.perform(post("/backtest-ui/promote-risk-params")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "",
                                  "strategyName": "p5 continuation",
                                  "isCall": false,
                                  "tpMultiplierDelta": 0.0,
                                  "slMultiplierDelta": 0.0,
                                  "dryRun": true
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ticker is required"));
    }

    @Test
    @DisplayName("POST promote-risk-params returns 200 with mocked body")
    void promoteOk() throws Exception {
        when(promoteRiskService.promote(any())).thenReturn(
                new com.fgiaquinta.optionsquant.backtest.grid.PromoteResult(true, "saved", 2.1, 2.5));

        mockMvc.perform(post("/backtest-ui/promote-risk-params")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ticker": "SPY",
                                  "strategyName": "p5 continuation",
                                  "isCall": false,
                                  "tpMultiplierDelta": 0.1,
                                  "slMultiplierDelta": 0.0,
                                  "dryRun": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persisted").value(true));

        verify(promoteRiskService).promote(any());
    }
}
