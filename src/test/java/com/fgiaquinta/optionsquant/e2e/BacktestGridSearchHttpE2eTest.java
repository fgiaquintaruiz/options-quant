package com.fgiaquinta.optionsquant.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.http.HttpResponse;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Full-stack HTTP contract for grid-search validation paths (fast failures, no full grid execution).
 * Parameterized cases: invalid bodies → 400. Slow cases: real engine (CI nocturno / {@code ./gradlew slowTest}).
 */
@Tag("e2e")
@DisplayName("Backtest grid-search HTTP (live app)")
class BacktestGridSearchHttpE2eTest extends BasePlaywrightTest {

    private static final String GRID_PATH = "/backtest-ui/grid-search";

    static Stream<Arguments> invalidGridSearchBodies() {
        return Stream.of(
                arguments("unknownAxis", """
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
                            { "name": "unknownAxis", "values": [0.0] }
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
                        """,
                        "unsupported axis"),
                arguments("emptyTickers", """
                        {
                          "tickers": [],
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
                        """,
                        "tickers"),
                arguments("walkForwardTrainOnly", """
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
                          "walkForwardTestDays": null,
                          "walkForwardStepDays": null
                        }
                        """,
                        "walkForwardTrainDays"),
                arguments("walkForwardPlusRandom", """
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
                            { "name": "tpMultiplierDelta", "values": [0.0, 0.1] },
                            { "name": "slMultiplierDelta", "values": [0.0, 0.2] }
                          ],
                          "searchMode": "RANDOM",
                          "randomSampleCount": 2,
                          "randomSeed": 1,
                          "constraintMinTrades": null,
                          "constraintMaxDrawdownPct": null,
                          "primaryMetric": "TOTAL_PNL",
                          "walkForwardTrainDays": 30,
                          "walkForwardTestDays": 14,
                          "walkForwardStepDays": 14
                        }
                        """,
                        "walk-forward"),
                arguments("walkForwardShortCalendarSpan", """
                        {
                          "tickers": ["SPY"],
                          "fromDate": "2025-01-01",
                          "toDate": "2025-01-20",
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
                        """,
                        "Date range too short"),
                arguments("duplicateAxisName", """
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
                            { "name": "tpMultiplierDelta", "values": [0.1] }
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
                        """,
                        "duplicate axis"),
                arguments("axisValueOutOfRange", """
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
                            { "name": "tpMultiplierDelta", "values": [11.0] },
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
                        """,
                        "out of range"),
                arguments("emptyAxes", """
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
                          "axes": [],
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
                        """,
                        "axes"),
                arguments("invalidSearchMode", """
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
                          "searchMode": "BOGUS",
                          "randomSampleCount": null,
                          "randomSeed": null,
                          "constraintMinTrades": null,
                          "constraintMaxDrawdownPct": null,
                          "primaryMetric": "TOTAL_PNL",
                          "walkForwardTrainDays": null,
                          "walkForwardTestDays": null,
                          "walkForwardStepDays": null
                        }
                        """,
                        "searchMode"),
                arguments("walkForwardTestOnly", """
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
                          "walkForwardTrainDays": null,
                          "walkForwardTestDays": 14,
                          "walkForwardStepDays": null
                        }
                        """,
                        "walkForwardTrainDays"),
                arguments("walkForwardZeroTrainDays", """
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
                          "walkForwardTrainDays": 0,
                          "walkForwardTestDays": 14,
                          "walkForwardStepDays": null
                        }
                        """,
                        ">= 1 day"),
                arguments("walkForwardStepZero", """
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
                          "walkForwardStepDays": 0
                        }
                        """,
                        "walkForwardStepDays"),
                arguments("randomModeMissingSampleCount", """
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
                          "searchMode": "RANDOM",
                          "randomSampleCount": null,
                          "randomSeed": null,
                          "constraintMinTrades": null,
                          "constraintMaxDrawdownPct": null,
                          "primaryMetric": "TOTAL_PNL",
                          "walkForwardTrainDays": null,
                          "walkForwardTestDays": null,
                          "walkForwardStepDays": null
                        }
                        """,
                        "randomSampleCount"),
                arguments("randomSampleExceedsCartesianSize", """
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
                            { "name": "tpMultiplierDelta", "values": [0.0, 0.1] },
                            { "name": "slMultiplierDelta", "values": [0.0, 0.2] }
                          ],
                          "searchMode": "RANDOM",
                          "randomSampleCount": 10,
                          "randomSeed": 1,
                          "constraintMinTrades": null,
                          "constraintMaxDrawdownPct": null,
                          "primaryMetric": "TOTAL_PNL",
                          "walkForwardTrainDays": null,
                          "walkForwardTestDays": null,
                          "walkForwardStepDays": null
                        }
                        """,
                        "Cartesian product"),
                arguments("axisNameBlank", """
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
                            { "name": "", "values": [0.0] },
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
                        """,
                        "blank"),
                arguments("axisValueNull", """
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
                            { "name": "tpMultiplierDelta", "values": [null, 0.0] },
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
                        """,
                        "out of range"),
                arguments("axisEmptyValueList", """
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
                            { "name": "tpMultiplierDelta", "values": [] },
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
                        """,
                        "must not be empty"),
                arguments("gridExceedsMaxCells", gridBodyExceedingConfiguredMaxCells(), "max-cells"));
    }

    /** 24×24 = 576 Cartesian cells ({@code > grid-search.max-cells} default 500). */
    private static String gridBodyExceedingConfiguredMaxCells() {
        String v = axisValuesJson(24, 0.1);
        return """
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
                    { "name": "tpMultiplierDelta", "values": %s },
                    { "name": "slMultiplierDelta", "values": %s }
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
                """.formatted(v, v);
    }

    private static String axisValuesJson(int count, double step) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format(java.util.Locale.US, "%.1f", i * step));
        }
        return sb.append("]").toString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidGridSearchBodies")
    @DisplayName("grid-search rejects invalid request bodies (400)")
    void invalidGridSearchReturns400(String caseName, String body, String bodyMustContain) throws Exception {
        HttpResponse<String> resp = postJsonBody(GRID_PATH, body, 400);
        assertTrue(resp.body().contains("error") || resp.body().toLowerCase().contains(bodyMustContain.toLowerCase()),
                caseName + " → " + resp.body());
    }

    @Test
    @Tag("slow")
    @DisplayName("grid-search 1×1 exhaustive returns JSON with optimization on success")
    void minimalGridReturns200() throws Exception {
        String body = """
                {
                  "tickers": ["SPY"],
                  "fromDate": "2025-01-01",
                  "toDate": "2025-02-15",
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
        HttpResponse<String> resp = postJsonBody(GRID_PATH, body, 200);
        JsonNode n = new ObjectMapper().readTree(resp.body());
        assertTrue(n.has("success"), () -> resp.body());
        assertTrue(n.has("optimization") || n.has("walkForwardSummary"), () -> resp.body());
    }

    @Test
    @Tag("slow")
    @DisplayName("grid-search walk-forward 1×1 returns walkForwardSummary (OOS aggregate)")
    void walkForwardMinimalGridReturns200() throws Exception {
        String body = """
                {
                  "tickers": ["SPY"],
                  "fromDate": "2025-01-01",
                  "toDate": "2025-06-30",
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
                  "walkForwardStepDays": 28
                }
                """;
        HttpResponse<String> resp = postJsonBody(GRID_PATH, body, 200);
        JsonNode n = new ObjectMapper().readTree(resp.body());
        assertTrue(n.path("success").asBoolean(false), () -> resp.body());
        assertTrue(n.has("walkForwardSummary"), () -> resp.body());
        JsonNode sum = n.get("walkForwardSummary");
        assertEquals("TOTAL_PNL", sum.path("primaryMetricUsed").asText(null), () -> resp.body());
        int foldsPlanned = sum.path("foldsPlanned").asInt(-1);
        int foldsWithOos = sum.path("foldsWithOosBacktest").asInt(0);
        assertTrue(foldsPlanned >= 1, () -> resp.body());
        assertTrue(foldsWithOos > 0 && foldsWithOos <= foldsPlanned, () -> resp.body());
        assertTrue(n.has("walkForwardFolds") && n.get("walkForwardFolds").isArray() && n.get("walkForwardFolds").size() > 0,
                () -> resp.body());
        JsonNode folds = n.get("walkForwardFolds");
        assertEquals(0, folds.get(0).path("foldIndex").asInt(-1), () -> resp.body());
        assertTrue(folds.get(0).has("trainFrom") && folds.get(0).has("testTo"), () -> resp.body());
    }
}
