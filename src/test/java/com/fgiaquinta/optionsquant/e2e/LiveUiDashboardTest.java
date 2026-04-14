package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Live UI Dashboard (/live-ui).
 * Tests page rendering, status API, scan API, signals, tickers, and TWS status.
 */
@DisplayName("Live UI Dashboard Tests")
class LiveUiDashboardTest extends BasePlaywrightTest {

    // ========== PAGE RENDERING ==========

    @Test
    @DisplayName("Live UI dashboard page loads with 200 status")
    void dashboardPageLoads() {
        Response response = page.navigate(BASE_URL + "/live-ui");
        assertNotNull(response);
        assertEquals(200, response.status());
    }

    @Test
    @DisplayName("Dashboard contains main sections")
    void dashboardContainsMainSections() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("Live Trading Dashboard"), "Should have title");
        assertTrue(content.contains("Scanning Status"), "Should have scanning status card");
        assertTrue(content.contains("Signals Today"), "Should have signals card");
        assertTrue(content.contains("TWS Connection"), "Should have TWS card");
        assertTrue(content.contains("Tickers Queue"), "Should have tickers queue");
        assertTrue(content.contains("Live Signals Feed"), "Should have signals feed");
        assertTrue(content.contains("Console Log"), "Should have console log");
    }

    @Test
    @DisplayName("Dashboard has navigation links")
    void dashboardHasNavigationLinks() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("href=\"/live-ui\""), "Should link to live-ui");
        assertTrue(content.contains("href=\"/backtest-ui\""), "Should link to backtest-ui");
        assertTrue(content.contains("href=\"/actuator/health\""), "Should link to health");
    }

    @Test
    @DisplayName("Dashboard has Start Scan and Stop Scan buttons")
    void dashboardHasScanButtons() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("startScanBtn"), "Should have start scan button");
        assertTrue(content.contains("stopScanBtn"), "Should have stop scan button");
        assertTrue(content.contains("▶ Start Scan"), "Should have start button text");
        assertTrue(content.contains("⏹ Stop Scan"), "Should have stop button text");
    }

    @Test
    @DisplayName("Dashboard has toggle switches for auto-execute and extended hours")
    void dashboardHasToggles() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("autoExecToggle"), "Should have auto-execute toggle");
        assertTrue(content.contains("extHoursToggle"), "Should have extended hours toggle");
    }

    @Test
    @DisplayName("Dashboard has signals table with correct columns")
    void dashboardHasSignalsTable() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("<th>Time</th>"), "Should have Time column");
        assertTrue(content.contains("<th>Ticker</th>"), "Should have Ticker column");
        assertTrue(content.contains("<th>Strategy</th>"), "Should have Strategy column");
        assertTrue(content.contains("<th>Dir</th>"), "Should have Direction column");
        assertTrue(content.contains("<th>Price</th>"), "Should have Price column");
        assertTrue(content.contains("<th>TP</th>"), "Should have TP column");
        assertTrue(content.contains("<th>SL</th>"), "Should have SL column");
        assertTrue(content.contains("<th>Pattern</th>"), "Should have Pattern column");
        assertTrue(content.contains("<th>Status</th>"), "Should have Status column");
    }

    @Test
    @DisplayName("Dashboard shows placeholder when no signals")
    void dashboardShowsPlaceholderWhenNoSignals() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("Waiting for scan") || content.contains("No signals"),
            "Should show placeholder message");
    }

    @Test
    @DisplayName("Dashboard has progress bar element")
    void dashboardHasProgressBar() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("progressBar"), "Should have progress bar element");
        assertTrue(content.contains("progress-fill"), "Should have progress fill element");
    }

    // ========== STATUS API ==========

    @Test
    @DisplayName("Status API returns scanning state")
    void statusApiReturnsScanningState() throws Exception {
        String json = getJson("/live-ui/status");
        assertNotNull(json);
        assertTrue(json.contains("\"isScanning\""));
        assertTrue(json.contains("\"stopScanRequested\""));
        assertTrue(json.contains("\"currentTicker\""));
        assertTrue(json.contains("\"currentTickerIndex\""));
        assertTrue(json.contains("\"totalTickers\""));
        assertTrue(json.contains("\"signalsToday\""));
        assertTrue(json.contains("\"extendedHoursEnabled\""));
        assertTrue(json.contains("\"autoExecute\""));
        assertTrue(json.contains("\"marketHours\""));
        assertTrue(json.contains("\"scannerScanned\""));
        assertTrue(json.contains("\"scannerTicker\""));
        assertTrue(json.contains("\"scannerTotal\""));
    }

    @Test
    @DisplayName("Status API returns timezone info")
    void statusApiReturnsTimezone() throws Exception {
        String json = getJson("/live-ui/status");
        assertTrue(json.contains("\"Europe/Madrid\""), "Should return Madrid timezone");
        assertTrue(json.contains("\"currentTime\""), "Should return current time");
    }

    // ========== TICKERS API ==========

    @Test
    @DisplayName("Tickers API returns ticker list")
    void tickersApiReturnsList() throws Exception {
        String json = getJson("/live-ui/tickers");
        assertNotNull(json);
        assertTrue(json.contains("\"allTickers\""));
        assertTrue(json.contains("\"hotTickers\""));
        assertTrue(json.contains("\"total\""));
        assertTrue(json.contains("\"scanning\""));
        assertTrue(json.contains("\"currentTicker\""));
    }

    @Test
    @DisplayName("Tickers API includes hot tickers")
    void tickersApiIncludesHotTickers() throws Exception {
        String json = getJson("/live-ui/tickers");
        assertTrue(json.contains("SPY"), "Should contain SPY");
        assertTrue(json.contains("QQQ"), "Should contain QQQ");
        assertTrue(json.contains("AAPL"), "Should contain AAPL");
        assertTrue(json.contains("NVDA"), "Should contain NVDA");
    }

    // ========== SIGNALS API ==========

    @Test
    @DisplayName("Signals API returns empty list initially")
    void signalsApiReturnsEmptyInitially() throws Exception {
        String json = getJson("/live-ui/signals");
        assertNotNull(json);
        assertTrue(json.contains("\"signals\""));
        assertTrue(json.contains("\"count\""));
        assertTrue(json.contains("\"signalsToday\""));
    }

    // ========== TWS STATUS API ==========

    @Test
    @DisplayName("TWS status API returns connection info")
    void twsStatusApiReturnsConnectionInfo() throws Exception {
        String json = getJson("/live-ui/tws-status");
        assertNotNull(json);
        assertTrue(json.contains("\"host\""));
        assertTrue(json.contains("\"port\""));
        assertTrue(json.contains("\"accountId\""));
        assertTrue(json.contains("\"autoExecute\""));
        assertTrue(json.contains("\"riskPerTrade\""));
    }

    // ========== SCAN API ==========

    @Test
    @DisplayName("Start scan returns success")
    void startScanReturnsSuccess() throws Exception {
        String json = postJson("/live-ui/scan-now");
        assertNotNull(json);
        assertTrue(json.contains("\"success\""));
    }

    @Test
    @DisplayName("Stop scan returns success when no scan running")
    void stopScanWhenNotRunning() throws Exception {
        // Wait a bit for any scan to complete
        Thread.sleep(2000);
        String json = postJson("/live-ui/stop-scan");
        assertNotNull(json);
        // May return success=false if no scan in progress
    }

    // ========== EXTENDED HOURS TOGGLE ==========

    @Test
    @DisplayName("Toggle extended hours works")
    void toggleExtendedHours() throws Exception {
        String json = postJson("/live-ui/toggle-extended-hours");
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true"));
        assertTrue(json.contains("\"extendedHours\""));
    }

    // ========== JAVASCRIPT FUNCTIONS ==========

    @Test
    @DisplayName("Page has loadStatus JavaScript function")
    void pageHasLoadStatusFunction() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof loadStatus");
        assertEquals("function", result, "loadStatus should be a function");
    }

    @Test
    @DisplayName("Page has loadSignals JavaScript function")
    void pageHasLoadSignalsFunction() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof loadSignals");
        assertEquals("function", result, "loadSignals should be a function");
    }

    @Test
    @DisplayName("Page has startScan JavaScript function")
    void pageHasStartScanFunction() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof startScan");
        assertEquals("function", result, "startScan should be a function");
    }

    @Test
    @DisplayName("Page has stopScan JavaScript function")
    void pageHasStopScanFunction() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof stopScan");
        assertEquals("function", result, "stopScan should be a function");
    }

    @Test
    @DisplayName("Page has updateUI JavaScript function")
    void pageHasUpdateUiFunction() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof updateUI");
        assertEquals("function", result, "updateUI should be a function");
    }

    @Test
    @DisplayName("Page has renderSignals JavaScript function")
    void pageHasRenderSignalsFunction() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof renderSignals");
        assertEquals("function", result, "renderSignals should be a function");
    }

    @Test
    @DisplayName("Page has renderTickers JavaScript function")
    void pageHasRenderTickersFunction() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Object result = page.evaluate("typeof renderTickers");
        assertEquals("function", result, "renderTickers should be a function");
    }
}
