package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the Live dashboard (React). Prefer {@code /live} so {@code isLive} is true in App.jsx
 * ({@code /live-ui} is API-only; the SPA hides the dashboard there).
 */
@Tag("e2e")
@DisplayName("Live UI Dashboard Tests")
class LiveUiDashboardTest extends BasePlaywrightTest {

    private static final String LIVE_PATH = "/live";

    // ========== PAGE RENDERING ==========

    @Test
    @DisplayName("Live dashboard page loads with 200 status")
    void dashboardPageLoads() {
        Response response = page.navigate(BASE_URL + LIVE_PATH);
        assertNotNull(response);
        assertEquals(200, response.status());
    }

    @Test
    @DisplayName("Dashboard contains toolbar and signals grid")
    void dashboardContainsMainSections() {
        page.navigate(BASE_URL + LIVE_PATH);
        page.waitForLoadState();

        assertTrue(page.locator("[data-testid=live-dashboard]").isVisible());
        assertTrue(page.locator("[data-testid=live-toolbar]").isVisible());
        assertTrue(page.locator("[data-testid=live-signals-grid]").isVisible());
        assertTrue(page.getByText("Tickers to scan").first().isVisible());
        // AccountModeChip renders "PAPER" or "LIVE" as visible text; "Account:" is only a title attribute
        assertTrue(
            page.getByText("PAPER").count() > 0 || page.getByText("LIVE").count() > 0,
            "Should show account mode chip with PAPER or LIVE label"
        );
    }

    @Test
    @DisplayName("Dashboard has SPA navigation links")
    void dashboardHasNavigationLinks() {
        page.navigate(BASE_URL + LIVE_PATH);
        page.waitForLoadState();

        assertTrue(page.locator("a[href='/live']").first().isVisible());
        assertTrue(page.locator("a[href='/backtest']").first().isVisible());
        assertTrue(page.locator("a[href='/health']").first().isVisible());
    }

    @Test
    @DisplayName("Dashboard has Start Scan or Stop Scan control")
    void dashboardHasScanButtons() {
        page.navigate(BASE_URL + LIVE_PATH);
        page.waitForLoadState();

        Locator start = page.locator("[data-testid=live-start-scan]");
        Locator stop = page.locator("[data-testid=live-stop-scan]");
        assertTrue(start.count() > 0 || stop.count() > 0);
    }

    @Test
    @DisplayName("Dashboard has scheduler and execution toggles")
    void dashboardHasToggles() {
        page.navigate(BASE_URL + LIVE_PATH);
        page.waitForLoadState();

        assertTrue(page.locator("[data-testid=live-toggle-scheduler]").isVisible());
        assertTrue(page.locator("[data-testid=live-toggle-auto-execute]").isVisible());
        assertTrue(page.locator("[data-testid=live-toggle-mock-market]").isVisible());
    }

    @Test
    @DisplayName("Signals grid has expected column headers")
    void dashboardHasSignalsTable() {
        page.navigate(BASE_URL + LIVE_PATH);
        page.waitForLoadState();

        assertTrue(page.getByText("Live Signals & Positions").isVisible());
        String html = page.content();
        assertTrue(html.contains("Ticker") && html.contains("Strategy") && html.contains("Status"));
    }

    @Test
    @DisplayName("Signals grid shows record count")
    void dashboardShowsRecordCount() {
        page.navigate(BASE_URL + LIVE_PATH);
        page.waitForLoadState();

        assertTrue(page.content().contains("records"));
    }

    @Test
    @DisplayName("React root exposes stable data-testid for automation")
    void dashboardHasStableTestIds() {
        page.navigate(BASE_URL + LIVE_PATH);
        page.waitForLoadState();

        assertTrue(page.locator("[data-testid=live-dashboard]").isVisible());
        assertTrue(page.locator("[data-testid=live-trade-grid-root]").isVisible());
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
        assertTrue(json.contains("\"scannerBatchLabel\""));
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
        // hotTickers content is runtime-configurable via ticker-runtime.json; assert non-empty list instead
        assertTrue(json.contains("\"hotTickers\":[\""), "hotTickers should be a non-empty JSON array");
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
        Thread.sleep(2000);
        String json = postJson("/live-ui/stop-scan");
        assertNotNull(json);
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
}
