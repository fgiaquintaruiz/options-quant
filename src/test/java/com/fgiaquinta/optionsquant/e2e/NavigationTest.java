package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SPA navigation uses React Router paths {@code /live}, {@code /backtest}, {@code /health} (see App.jsx).
 */
@Tag("e2e")
@DisplayName("Navigation Tests")
class NavigationTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Can navigate from Live to Backtest via link")
    void navigateLiveToBacktest() {
        page.navigate(BASE_URL + "/live");
        page.waitForLoadState();

        Locator backtestLink = page.locator("a[href='/backtest']").first();
        assertTrue(backtestLink.isVisible(), "Backtest link should be visible");
        backtestLink.click();
        page.waitForLoadState();

        assertTrue(page.content().contains("Run Backtest"), "Should be on backtest page");
    }

    @Test
    @DisplayName("Can navigate from Backtest to Live via link")
    void navigateBacktestToLive() {
        page.navigate(BASE_URL + "/backtest");
        page.waitForLoadState();

        Locator liveLink = page.locator("a[href='/live']").first();
        assertTrue(liveLink.isVisible(), "Live link should be visible");
        liveLink.click();
        page.waitForLoadState();

        assertTrue(page.locator("[data-testid=live-dashboard]").isVisible(), "Should be on live page");
    }

    @Test
    @DisplayName("Can navigate from Live to Health via link")
    void navigateLiveToHealth() {
        page.navigate(BASE_URL + "/live");
        page.waitForLoadState();

        Locator healthLink = page.locator("a[href='/health']").first();
        assertTrue(healthLink.isVisible(), "Health link should be visible");
        healthLink.click();
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("health") || content.contains("Health") || content.contains("UP"),
            "Should show health-related content");
    }

    @Test
    @DisplayName("Can navigate from Backtest to Health via link")
    void navigateBacktestToHealth() {
        page.navigate(BASE_URL + "/backtest");
        page.waitForLoadState();

        Locator healthLink = page.locator("a[href='/health']").first();
        assertTrue(healthLink.isVisible());
        healthLink.click();
        page.waitForLoadState();
        assertTrue(page.content().toLowerCase().contains("health"));
    }

    @Test
    @DisplayName("Can navigate Live → Backtest → Live")
    void navigateRoundTrip() {
        page.navigate(BASE_URL + "/live");
        page.waitForLoadState();

        page.locator("a[href='/backtest']").first().click();
        page.waitForLoadState();
        assertTrue(page.content().contains("Run Backtest"));

        page.locator("a[href='/live']").first().click();
        page.waitForLoadState();
        assertTrue(page.locator("[data-testid=live-dashboard]").isVisible());
    }

    @Test
    @DisplayName("Direct URL access returns 200 for SPA and actuator")
    void directUrlAccess() {
        assertEquals(200, page.navigate(BASE_URL + "/live").status());
        assertEquals(200, page.navigate(BASE_URL + "/backtest").status());
        assertEquals(200, page.navigate(BASE_URL + "/actuator/health").status());
    }

    @Test
    @DisplayName("Page title is Options Quant on main routes")
    void pageTitleCorrect() {
        page.navigate(BASE_URL + "/live");
        page.waitForLoadState();
        assertEquals("Options Quant", page.title());

        page.navigate(BASE_URL + "/backtest");
        page.waitForLoadState();
        assertEquals("Options Quant", page.title());
    }

    @Test
    @DisplayName("Main routes serve HTML shell")
    void pagesHaveHtmlStructure() {
        for (String path : new String[] { "/live", "/backtest" }) {
            page.navigate(BASE_URL + path);
            page.waitForLoadState();
            String content = page.content();
            assertTrue(content.contains("<html"), path + " should have html");
            assertTrue(content.contains("root") || content.contains("root\""), path + " should mount React");
        }
    }
}
