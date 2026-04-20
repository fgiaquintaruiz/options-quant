package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E checks for the React backtest page ({@code /backtest}) and {@code /backtest-ui} REST APIs.
 * The legacy HTML dashboard at {@code /backtest-ui} redirects to {@code /}; the SPA route is {@code /backtest}.
 */
@Tag("e2e")
@DisplayName("Backtest UI Dashboard Tests")
class BacktestUiDashboardTest extends BasePlaywrightTest {

    private static final String BT_PAGE = "/backtest";

    /** Live and backtest pages share labels like "Tickers to scan"; scope DOM queries to the backtest panel. */
    private Locator backtestRoot() {
        return page.locator("[data-testid=backtest-dashboard]");
    }

    @BeforeEach
    void clearCheckpointForDeterministicApiTests() throws Exception {
        postJson("/backtest-ui/checkpoint/clear");
    }

    // ========== PAGE RENDERING ==========

    @Test
    @DisplayName("Backtest SPA loads with 200 status")
    void dashboardPageLoads() {
        Response response = page.navigate(BASE_URL + BT_PAGE);
        assertNotNull(response);
        assertEquals(200, response.status());
    }

    @Test
    @DisplayName("Dashboard contains main sections")
    void dashboardContainsMainSections() {
        page.navigate(BASE_URL + BT_PAGE);
        page.waitForLoadState();
        backtestRoot().waitFor();

        assertTrue(backtestRoot().getByText("Tickers to scan").isVisible());
        assertTrue(backtestRoot().getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Run Backtest")).isVisible());
        assertTrue(backtestRoot().getByText("Trade log").isVisible());
    }

    @Test
    @DisplayName("Dashboard has Run Backtest button")
    void dashboardHasRunButton() {
        page.navigate(BASE_URL + BT_PAGE);
        page.waitForLoadState();
        backtestRoot().waitFor();
        assertTrue(backtestRoot().getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Run Backtest")).isVisible());
    }

    @Test
    @DisplayName("Dashboard has SPA navigation links")
    void dashboardHasNavigationLinks() {
        page.navigate(BASE_URL + BT_PAGE);
        page.waitForLoadState();
        assertTrue(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Loving Mode")).isVisible());
        assertTrue(page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("Loved Mode")).isVisible());
    }

    @Test
    @DisplayName("Dashboard loads Vite bundle (Recharts lives in JS chunk)")
    void dashboardLoadsReactBundle() {
        page.navigate(BASE_URL + BT_PAGE);
        page.waitForLoadState();
        Locator script = page.locator("script[src*='assets/index-']").first();
        assertTrue(script.count() > 0, "Should load hashed main bundle from /static/assets/");
    }

    @Test
    @DisplayName("Dashboard exposes concurrent scan control")
    void dashboardHasConcurrentControl() {
        page.navigate(BASE_URL + BT_PAGE);
        page.waitForLoadState();
        backtestRoot().waitFor();
        assertTrue(backtestRoot().getByText("Concurrent:").isVisible());
        assertTrue(backtestRoot().locator("input[type='number']").isVisible());
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

    // ========== NO LEGACY GLOBAL FUNCTIONS (React SPA) ==========

    @Test
    @DisplayName("Page does not expose legacy window.runBacktest (React handlers instead)")
    void pageDoesNotExposeLegacyGlobals() {
        page.navigate(BASE_URL + BT_PAGE);
        page.waitForLoadState();

        Object runBacktest = page.evaluate("typeof window.runBacktest");
        Object improveStrategy = page.evaluate("typeof window.improveStrategy");
        assertEquals("undefined", runBacktest);
        assertEquals("undefined", improveStrategy);
    }
}
