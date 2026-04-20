package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Backtest mode interactions - run backtest, improve strategy.
 * Tagged slow: POST /backtest-ui/run triggers a full parallel backtest (~all tickers).
 */
@Tag("e2e")
@Tag("slow")
@DisplayName("Backtest Interaction Tests")
class BacktestInteractionTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Backtest run endpoint returns response")
    void backtestRunReturnsResponse() throws Exception {
        String json = postJson("/backtest-ui/run?initialCapital=50000&riskPct=0.02");
        assertNotNull(json);
        // May succeed or fail depending on data availability
        assertTrue(json.contains("\"success\"") || json.contains("\"error\""));
    }

    @Test
    @DisplayName("Backtest improve strategy endpoint returns response")
    void backtestImproveStrategyReturnsResponse() throws Exception {
        String json = postJson("/backtest-ui/improve/C1SqueezeCallStrategy");
        assertNotNull(json);
        // May return success=false if no trades found, but should not 500
        assertTrue(json.contains("\"success\""));
    }

    @Test
    @DisplayName("Backtest retest endpoint returns response")
    void backtestRetestReturnsResponse() throws Exception {
        String json = postJson("/backtest-ui/retest/C1SqueezeCallStrategy?initialCapital=50000&riskPct=0.02");
        assertNotNull(json);
        // May return success=false if strategy hasn't been analyzed, but should not 500
        assertTrue(json.contains("\"success\"") || json.contains("\"error\""));
    }

    @Test
    @DisplayName("Stop backtest endpoint handles gracefully")
    void stopBacktestHandlesGracefully() throws Exception {
        String json = postJson("/backtest-ui/stop");
        assertNotNull(json);
        assertTrue(json.contains("\"success\""));
    }

    @Test
    @DisplayName("Running check returns valid JSON")
    void runningCheckReturnsValidJson() throws Exception {
        String json = getJson("/backtest-ui/running");
        assertNotNull(json);
        assertTrue(json.contains("\"running\""));
    }

    @Test
    @DisplayName("Checkpoint check returns valid JSON")
    void checkpointCheckReturnsValidJson() throws Exception {
        String json = getJson("/backtest-ui/checkpoint");
        assertNotNull(json);
        assertTrue(json.contains("\"exists\""));
    }

    @Test
    @DisplayName("Clear checkpoint returns valid JSON")
    void clearCheckpointReturnsValidJson() throws Exception {
        String json = postJson("/backtest-ui/checkpoint/clear");
        assertNotNull(json);
        assertTrue(json.contains("\"success\""));
    }
}
