package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Backtest UI Dashboard (/backtest-ui).
 * Tests page rendering, run API, stop API, running check, checkpoint, and resume endpoints.
 */
@DisplayName("Backtest UI Dashboard Tests")
class BacktestUiDashboardTest extends BasePlaywrightTest {

    // ========== PAGE RENDERING ==========

    @Test
    @DisplayName("Backtest UI dashboard page loads with 200 status")
    void dashboardPageLoads() {
        Response response = page.navigate(BASE_URL + "/backtest-ui");
        assertNotNull(response);
        assertEquals(200, response.status());
    }

    @Test
    @DisplayName("Dashboard contains main sections")
    void dashboardContainsMainSections() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("OPTIONSQUANT TRADING BACKTEST DASHBOARD"), "Should have title");
        assertTrue(content.contains("RUN BACKTEST"), "Should have run backtest section");
        assertTrue(content.contains("TRADES EN VIVO"), "Should have live trades section");
        assertTrue(content.contains("RESULTADOS"), "Should have results section");
        assertTrue(content.contains("CURVA DE EQUITY"), "Should have equity curve section");
        assertTrue(content.contains("PERFORMANCE POR ESTRATEGIA"), "Should have strategy performance");
        assertTrue(content.contains("PERFORMANCE POR TICKER"), "Should have ticker performance");
        assertTrue(content.contains("IMPROVE STRATEGY"), "Should have improve strategy section");
    }

    @Test
    @DisplayName("Dashboard has Run Backtest button")
    void dashboardHasRunButton() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("runBacktest") || content.contains("Run Backtest"),
            "Should have run backtest button");
    }

    @Test
    @DisplayName("Dashboard has navigation links")
    void dashboardHasNavigationLinks() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("href=\"/backtest-ui\""), "Should link to backtest-ui");
        assertTrue(content.contains("href=\"/live-ui\""), "Should link to live-ui");
    }

    @Test
    @DisplayName("Dashboard has Chart.js integration")
    void dashboardHasChartJs() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("Chart") || content.contains("chart"), "Should reference Chart.js");
        assertTrue(content.contains("equity-chart") || content.contains("equityChart"), "Should have equity chart element");
    }

    @Test
    @DisplayName("Dashboard has improve strategy modal")
    void dashboardHasImproveModal() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("improve-modal") || content.contains("improveModal"),
            "Should have improve strategy modal");
        assertTrue(content.contains("improveStrategy"), "Should have improveStrategy function");
    }

    @Test
    @DisplayName("Dashboard has retest functionality")
    void dashboardHasRetestFunctionality() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("retestStrategy") || content.contains("retest"),
            "Should have retest functionality");
    }

    // ========== RUNNING CHECK API ==========

    @Test
    @DisplayName("Running check API returns boolean state")
    void runningCheckApiReturnsState() throws Exception {
        String json = getJson("/backtest-ui/running");
        assertNotNull(json);
        assertTrue(json.contains("\"running\""));
    }

    @Test
    @DisplayName("Running check returns false when no backtest running")
    void runningCheckReturnsFalseInitially() throws Exception {
        String json = getJson("/backtest-ui/running");
        assertTrue(json.contains("\"running\":false"), "Should not be running initially");
    }

    // ========== STOP API ==========

    @Test
    @DisplayName("Stop API returns response when no backtest running")
    void stopApiReturnsWhenNotRunning() throws Exception {
        String json = postJson("/backtest-ui/stop");
        assertNotNull(json);
        // Returns success=false when nothing is running, or success=true if stop was requested
        assertTrue(json.contains("\"success\""));
    }

    // ========== CHECKPOINT API ==========

    @Test
    @DisplayName("Checkpoint API returns exists status")
    void checkpointApiReturnsExistsStatus() throws Exception {
        String json = getJson("/backtest-ui/checkpoint");
        assertNotNull(json);
        assertTrue(json.contains("\"exists\""));
    }

    @Test
    @DisplayName("Checkpoint API returns false when no checkpoint")
    void checkpointApiReturnsFalseWhenNone() throws Exception {
        String json = getJson("/backtest-ui/checkpoint");
        assertTrue(json.contains("\"exists\":false"), "Should not exist initially");
    }

    @Test
    @DisplayName("Clear checkpoint API works")
    void clearCheckpointApiWorks() throws Exception {
        String json = postJson("/backtest-ui/checkpoint/clear");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
    }

    // ========== JAVASCRIPT FUNCTIONS ==========

    @Test
    @DisplayName("Page has runBacktest JavaScript function")
    void pageHasRunBacktestFunction() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof runBacktest");
        assertEquals("function", result, "runBacktest should be a function");
    }

    @Test
    @DisplayName("Page has improveStrategy JavaScript function")
    void pageHasImproveStrategyFunction() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof improveStrategy");
        assertEquals("function", result, "improveStrategy should be a function");
    }

    @Test
    @DisplayName("Page has displayResults JavaScript function")
    void pageHasDisplayResultsFunction() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof displayResults");
        assertEquals("function", result, "displayResults should be a function");
    }

    @Test
    @DisplayName("Page has drawEquityChart JavaScript function")
    void pageHasDrawEquityChartFunction() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof drawEquityChart");
        assertEquals("function", result, "drawEquityChart should be a function");
    }

    @Test
    @DisplayName("Page has drawStrategyTable JavaScript function")
    void pageHasDrawStrategyTableFunction() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof drawStrategyTable");
        assertEquals("function", result, "drawStrategyTable should be a function");
    }

    @Test
    @DisplayName("Page has drawTickerTable JavaScript function")
    void pageHasDrawTickerTableFunction() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof drawTickerTable");
        assertEquals("function", result, "drawTickerTable should be a function");
    }

    @Test
    @DisplayName("Page has closeModal JavaScript function")
    void pageHasCloseModalFunction() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof closeModal");
        assertEquals("function", result, "closeModal should be a function");
    }
}
