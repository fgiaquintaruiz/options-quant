package com.fgiaquinta.optionsquant.e2e;

import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: toggling "Auto Run" on the backtest page caused the UI to vanish after a delay.
 * Collects console errors and URL to make failures diagnosable.
 */
@Tag("e2e")
@DisplayName("Backtest Auto Run stability")
class BacktestAutoRunStabilityTest extends BasePlaywrightTest {

    private static final String BT = "/backtest";
    /** Long soak: intermittent UI bugs / SSE updates can take time to surface */
    private static final int WAIT_SECONDS = 240;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    @DisplayName("After enabling Auto Run, backtest dashboard stays visible (soak, console errors captured)")
    void autoRunToggle_dashboardRemainsVisible() throws Exception {
        List<String> consoleErrors = new CopyOnWriteArrayList<>();
        List<String> pageErrors = new CopyOnWriteArrayList<>();
        List<String> warnings = new CopyOnWriteArrayList<>();
        List<String> mainFrameNavigations = new CopyOnWriteArrayList<>();

        page.onFrameNavigated(frame -> {
            if (frame.equals(page.mainFrame())) {
                mainFrameNavigations.add(frame.url());
            }
        });

        page.onConsoleMessage((ConsoleMessage msg) -> {
            String t = msg.type();
            String text = msg.text();
            if ("error".equals(t)) {
                consoleErrors.add(text);
            } else if ("warning".equals(t)) {
                warnings.add(text);
            }
        });
        page.onPageError(err -> pageErrors.add(err));

        Response response = page.navigate(BASE_URL + BT);
        assertNotNull(response);
        assertEquals(200, response.status(), "Navigate to /backtest");

        page.waitForLoadState();
        Locator root = page.locator("[data-testid=backtest-dashboard]");
        root.waitFor();

        assertTrue(root.isVisible(), "Backtest dashboard should be visible after load");

        Locator toggle = page.getByTestId("backtest-auto-run-toggle");
        toggle.waitFor();
        String label = toggle.innerText().trim();
        if (label.contains("Manual")) {
            toggle.click();
            page.waitForTimeout(400);
        }

        String afterToggle = toggle.innerText().trim();
        assertTrue(afterToggle.contains("Auto") || afterToggle.contains("Manual"),
                "Toggle should show Auto/Manual label; got: " + afterToggle);

        for (int i = 0; i < WAIT_SECONDS; i++) {
            boolean visible = root.isVisible();
            String url = page.url();
            if (!visible || !url.contains("/backtest")) {
                Path shot = Path.of("build", "backtest-autorun-failure.png");
                try {
                    page.screenshot(new Page.ScreenshotOptions().setPath(shot).setFullPage(true));
                } catch (Exception ignored) {
                }
                String detail = String.format(
                        "Failed after ~%ds. visible=%s url=%s%nmainFrameNavHistory=%s%nconsoleErrors=%s%npageErrors=%s%nwarnings(first10)=%s",
                        i + 1,
                        visible,
                        url,
                        String.join(" -> ", mainFrameNavigations),
                        String.join(" | ", consoleErrors),
                        String.join(" | ", pageErrors),
                        warnings.stream().limit(10).collect(Collectors.joining(" | "))
                );
                fail(detail);
            }
            Thread.sleep(1000);
        }

        if (!consoleErrors.isEmpty() || !pageErrors.isEmpty()) {
            System.out.println("[BacktestAutoRunStabilityTest] Console errors: " + consoleErrors);
            System.out.println("[BacktestAutoRunStabilityTest] Page errors: " + pageErrors);
        }
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    @DisplayName("Auto Run already enabled in localStorage: dashboard stays visible (soak)")
    void autoRunAlreadyEnabledInLocalStorage_staysVisible() throws Exception {
        List<String> consoleErrors = new CopyOnWriteArrayList<>();
        List<String> pageErrors = new CopyOnWriteArrayList<>();
        List<String> mainFrameNavigations = new CopyOnWriteArrayList<>();

        page.onFrameNavigated(frame -> {
            if (frame.equals(page.mainFrame())) {
                mainFrameNavigations.add(frame.url());
            }
        });
        page.onConsoleMessage((ConsoleMessage msg) -> {
            if ("error".equals(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });
        page.onPageError(pageErrors::add);

        page.navigate(BASE_URL + BT);
        page.waitForLoadState();
        page.evaluate("async () => { await fetch('/backtest-ui/scheduler', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ enabled: true, initialCapital: 50000, riskPct: 0.02, tickerFilter: '', tickerScope: 'HOT' }) }); }");
        page.reload();
        page.waitForLoadState();

        Locator root = page.locator("[data-testid=backtest-dashboard]");
        root.waitFor();

        assertTrue(page.getByTestId("backtest-auto-run-toggle").innerText().contains("Auto"),
                "Toggle should reflect Auto Run when server scheduler is enabled");

        for (int i = 0; i < WAIT_SECONDS; i++) {
            if (!root.isVisible() || !page.url().contains("/backtest")) {
                fail("Failed after ~" + (i + 1) + "s url=" + page.url()
                        + " nav=" + String.join(" -> ", mainFrameNavigations)
                        + " console=" + consoleErrors + " pageErr=" + pageErrors);
            }
            Thread.sleep(1000);
        }
    }
}
