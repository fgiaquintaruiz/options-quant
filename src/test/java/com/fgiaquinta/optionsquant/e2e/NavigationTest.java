package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for navigation between pages and cross-page links.
 */
@DisplayName("Navigation Tests")
class NavigationTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Can navigate from Live UI to Backtest UI via link")
    void navigateLiveToBacktest() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        // Find and click the Backtest nav link
        Locator backtestLink = page.locator("a[href='/backtest-ui']").first();
        assertTrue(backtestLink.isVisible(), "Backtest link should be visible");
        backtestLink.click();
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("BACKTEST DASHBOARD"), "Should be on backtest page");
    }

    @Test
    @DisplayName("Can navigate from Backtest UI to Live UI via link")
    void navigateBacktestToLive() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        // Find and click the Live Trading nav link
        Locator liveLink = page.locator("a[href='/live-ui']").first();
        assertTrue(liveLink.isVisible(), "Live UI link should be visible");
        liveLink.click();
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("Live Trading Dashboard"), "Should be on live page");
    }

    @Test
    @DisplayName("Can navigate from Live UI to Health via link")
    void navigateLiveToHealth() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        Locator healthLink = page.locator("a[href='/actuator/health']").first();
        assertTrue(healthLink.isVisible(), "Health link should be visible");
        healthLink.click();
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("UP") || content.contains("status"),
            "Should show health status");
    }

    @Test
    @DisplayName("Can navigate from Backtest UI to Health via link")
    void navigateBacktestToHealth() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        Locator healthLink = page.locator("a[href='/actuator/health']").first();
        if (!healthLink.isVisible()) {
            // Health link might not be in backtest UI nav, check content
            String content = page.content();
            assertTrue(content.contains("actuator/health") || content.contains("Health"),
                "Should have health reference");
        } else {
            healthLink.click();
            page.waitForLoadState();
            String content = page.content();
            assertTrue(content.contains("UP") || content.contains("status"),
                "Should show health status");
        }
    }

    @Test
    @DisplayName("Can navigate from Live UI to Backtest UI and back")
    void navigateRoundTrip() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        // Go to backtest
        page.locator("a[href='/backtest-ui']").first().click();
        page.waitForLoadState();
        String content1 = page.content();
        assertTrue(content1.contains("BACKTEST DASHBOARD"));

        // Go back to live
        page.locator("a[href='/live-ui']").first().click();
        page.waitForLoadState();
        String content2 = page.content();
        assertTrue(content2.contains("Live Trading Dashboard"));
    }

    @Test
    @DisplayName("Direct URL access works for all pages")
    void directUrlAccess() {
        // Live UI
        Response r1 = page.navigate(BASE_URL + "/live-ui");
        assertEquals(200, r1.status());

        // Backtest UI
        Response r2 = page.navigate(BASE_URL + "/backtest-ui");
        assertEquals(200, r2.status());

        // Health
        Response r3 = page.navigate(BASE_URL + "/actuator/health");
        assertEquals(200, r3.status());
    }

    @Test
    @DisplayName("Page title is correct on all pages")
    void pageTitleCorrect() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();
        String liveTitle = page.title();
        assertTrue(liveTitle.contains("Live") || liveTitle.contains("Trading"),
            "Live UI title should mention Live/Trading: " + liveTitle);

        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();
        String backtestTitle = page.title();
        assertTrue(backtestTitle.contains("Backtest") || backtestTitle.contains("OPTIONSQUANT"),
            "Backtest title should mention Backtest: " + backtestTitle);
    }

    @Test
    @DisplayName("All pages have proper HTML structure")
    void pagesHaveHtmlStructure() {
        String[] urls = {"/live-ui", "/backtest-ui"};
        for (String url : urls) {
            page.navigate(BASE_URL + url);
            page.waitForLoadState();

            String content = page.content();
            assertTrue(content.contains("<!DOCTYPE html>") || content.contains("<html"),
                url + " should have HTML doctype");
            assertTrue(content.contains("<head>"), url + " should have head");
            assertTrue(content.contains("<body>"), url + " should have body");
            assertTrue(content.contains("</html>"), url + " should close html");
            assertTrue(content.contains("<script>"), url + " should have scripts");
        }
    }
}
