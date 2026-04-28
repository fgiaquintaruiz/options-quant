package com.fgiaquinta.optionsquant.e2e;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Requires TWS connected (paper account): {@code /replay/start} delegates to OrderExecutionService → IBKR.
 * Tag {@code tws-paper} marks it as TWS-dependent so CI can exclude it without skipping all e2e tests.
 */
@Tag("e2e")
@Tag("tws-paper")
@DisplayName("ReplayControls E2E")
class ReplayControlsE2eTest extends BasePlaywrightTest {

    @Test
    @DisplayName("Toggle Mock Mkt then start and stop replay via UI")
    void replayControlsHappyPath_togglesMockMktThenStartsAndStopsReplay() {
        // Step 1 — navigate to live dashboard
        page.navigate(BASE_URL + "/live");

        // Step 2 — wait for dashboard root to be visible
        assertThat(page.locator("[data-testid=live-dashboard]")).isVisible();

        // Step 3 — activate Mock Mkt so ReplayControls renders
        page.locator("[data-testid=live-toggle-mock-market]").click();

        // Step 4 — ReplayControls is now in the DOM; wait for start button
        Locator startBtn = page.locator("[data-testid=replay-start-btn]");
        startBtn.waitFor(new Locator.WaitForOptions().setTimeout(5000));

        // Step 5 — ensure clean initial state: replay must be inactive (btn-primary).
        // If the backend already has an active replay session from a prior run, stop it first.
        boolean alreadyActive = startBtn.getAttribute("class").contains("btn-secondary");
        if (alreadyActive) {
            startBtn.click();
            // Wait for btn-primary to confirm backend acknowledged the stop
            startBtn.waitFor(new Locator.WaitForOptions().setTimeout(5000));
            waitForClass(startBtn, "btn-primary", 5000);
        }

        // Step 6 — click Play → starts replay
        startBtn.click();

        // Step 7 — optimistic update + polling must confirm active: true → btn-secondary
        waitForClass(startBtn, "btn-secondary", 8000);

        // Step 8 — click Stop → stops replay
        startBtn.click();

        // Step 9 — polling must confirm active: false → btn-primary
        waitForClass(startBtn, "btn-primary", 8000);
    }

    /**
     * Polls the button's class attribute until it contains {@code expectedClass} or the
     * timeout expires. Playwright's built-in {@code assertThat().hasClass()} matches against
     * the FULL class string as a regex, which would fail on multi-class values like
     * {@code "btn btn-primary ld-replay-start"}. Polling getAttribute is safer here.
     */
    private void waitForClass(Locator locator, String expectedClass, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String cls = locator.getAttribute("class");
            if (cls != null && cls.contains(expectedClass)) return;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        // Final assertion — surfaces a clear failure message if still not matching
        String actual = locator.getAttribute("class");
        if (actual == null || !actual.contains(expectedClass)) {
            throw new AssertionError(
                "Expected replay-start-btn to have class '" + expectedClass +
                "' but was: '" + actual + "'");
        }
    }
}
