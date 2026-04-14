package com.fgiaquinta.optionsquant.e2e;

import org.junit.jupiter.api.*;
import com.microsoft.playwright.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UI elements styling and visual structure.
 */
@DisplayName("UI Styling Tests")
class UiStylingTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Live UI has CSS styles defined")
    void liveUiHasCssStyles() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("<style>"), "Should have style block");
        assertTrue(content.contains("font-family"), "Should define font-family");
        assertTrue(content.contains("background"), "Should define background colors");
    }

    @Test
    @DisplayName("Live UI uses dark theme colors")
    void liveUiUsesDarkTheme() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("#0d1117") || content.contains("#161b22") || content.contains("#21262d"),
            "Should use GitHub dark theme colors");
    }

    @Test
    @DisplayName("Live UI has card-based layout")
    void liveUiHasCardLayout() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains(".card"), "Should define card class");
        assertTrue(content.contains("grid"), "Should use grid layout");
    }

    @Test
    @DisplayName("Live UI has badge styles for hot tickers")
    void liveUiHasBadgeStyles() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains(".badge"), "Should define badge class");
        assertTrue(content.contains(".badge-hot"), "Should define badge-hot class");
    }

    @Test
    @DisplayName("Live UI has toggle switch styles")
    void liveUiHasToggleStyles() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains(".toggle"), "Should define toggle class");
        assertTrue(content.contains(".toggle.active"), "Should define toggle.active class");
    }

    @Test
    @DisplayName("Live UI has progress bar styles")
    void liveUiHasProgressBarStyles() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains(".progress-bar"), "Should define progress-bar class");
        assertTrue(content.contains(".progress-fill"), "Should define progress-fill class");
    }

    @Test
    @DisplayName("Live UI has table styles for signals")
    void liveUiHasTableStyles() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains(".signals-table"), "Should define signals-table class");
        assertTrue(content.contains("border-bottom"), "Should have border styling");
    }

    @Test
    @DisplayName("Live UI has console log styles")
    void liveUiHasConsoleLogStyles() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains(".console-log"), "Should define console-log class");
        assertTrue(content.contains(".log-info"), "Should define log-info class");
        assertTrue(content.contains(".log-success"), "Should define log-success class");
        assertTrue(content.contains(".log-error"), "Should define log-error class");
    }

    @Test
    @DisplayName("Backtest UI has CSS styles defined")
    void backtestUiHasCssStyles() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("<style>") || content.contains("class="),
            "Should have styles or classes");
        assertTrue(content.contains("background"), "Should define background colors");
    }

    @Test
    @DisplayName("Backtest UI uses dark theme colors")
    void backtestUiUsesDarkTheme() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("#0d1117") || content.contains("#161b22") ||
                   content.contains("#21262d") || content.contains("dark"),
            "Should use dark theme colors");
    }

    @Test
    @DisplayName("Backtest UI has responsive design")
    void backtestUiHasResponsiveDesign() {
        page.navigate(BASE_URL + "/backtest-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("viewport") || content.contains("width=device-width"),
            "Should have viewport meta tag for responsive design");
    }

    @Test
    @DisplayName("Live UI page is visible on mobile viewport")
    void liveUiVisibleOnMobile() {
        page.navigate(BASE_URL + "/live-ui");
        page.setViewportSize(375, 812); // iPhone X size
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains("Live Trading"), "Should still show content on mobile");
    }

    @Test
    @DisplayName("Live UI positive/negative color classes exist")
    void liveUiHasPositiveNegativeColors() {
        page.navigate(BASE_URL + "/live-ui");
        page.waitForLoadState();

        String content = page.content();
        assertTrue(content.contains(".positive") || content.contains("positive"),
            "Should define positive class for gains");
        assertTrue(content.contains(".negative") || content.contains("negative"),
            "Should define negative class for losses");
    }
}
