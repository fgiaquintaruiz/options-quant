package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke checks that the Vite bundle applies expected utility classes (see frontend/src/index.css).
 */
@Tag("e2e")
@DisplayName("UI Styling Tests")
class UiStylingTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Live page loads stylesheet and card layout")
    void liveUiLoadsAssets() {
        page.navigate(BASE_URL + "/live");
        page.waitForLoadState();
        assertTrue(page.content().contains("stylesheet") || page.content().contains(".css"),
            "Should reference built CSS");
        assertTrue(page.locator(".card").count() > 0, "Should render .card regions");
    }

    @Test
    @DisplayName("Live dashboard uses theme colors in DOM")
    void liveUiUsesThemeClasses() {
        page.navigate(BASE_URL + "/live");
        page.waitForLoadState();
        String html = page.content();
        assertTrue(html.contains("color-muted") || html.contains("card"), "Expected utility classes from index.css");
    }

    @Test
    @DisplayName("Backtest page renders main shell")
    void backtestUiLoads() {
        page.navigate(BASE_URL + "/backtest");
        page.waitForLoadState();
        assertTrue(page.content().contains("Run Backtest") || page.content().contains("card"),
            "Backtest dashboard should render");
    }

    @Test
    @DisplayName("Live layout visible on narrow viewport")
    void liveUiVisibleOnMobile() {
        page.setViewportSize(375, 812);
        page.navigate(BASE_URL + "/live");
        page.waitForLoadState();
        assertTrue(page.locator("[data-testid=live-dashboard]").isVisible());
    }
}
