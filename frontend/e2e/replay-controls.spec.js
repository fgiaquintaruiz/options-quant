import { test, expect } from '@playwright/test';

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Install route mocks that the replay flow needs.
 * Called at the start of every test that exercises the replay lifecycle.
 *
 * Mocked endpoints:
 *  - GET /live-ui/replay/status   → inactive by default (overridden per-test when needed)
 *  - POST /live-ui/replay/start   → { success: true }
 *  - POST /live-ui/replay/stop    → { success: true }
 *
 * The status mock is returned as a factory so individual tests can
 * override the response by re-registering the route.
 */
async function mockReplayInactive(page) {
  await page.route('**/live-ui/replay/status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ active: false, virtualNow: null, speed: 0 }),
    })
  );
}

async function mockReplayActive(page, virtualNow = '2026-04-27T09:30:00-05:00[America/New_York]') {
  await page.route('**/live-ui/replay/status', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ active: true, virtualNow, speed: 30 }),
    })
  );
}

async function mockReplayStart(page) {
  await page.route('**/live-ui/replay/start**', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true }),
    })
  );
}

async function mockReplayStop(page) {
  await page.route('**/live-ui/replay/stop', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true }),
    })
  );
}

/**
 * Open the Live Dashboard and expand the Advanced panel.
 * Returns only after the Advanced dropdown is visible.
 *
 * Pre-condition: route mocks must already be registered before calling this.
 */
async function openAdvancedPanel(page) {
  await page.goto('/');
  // The Advanced trigger is always visible in the toolbar
  const trigger = page.getByTestId('advanced-scan-trigger');
  await trigger.waitFor({ state: 'visible' });
  await trigger.click();
  await page.getByTestId('advanced-scan-dropdown').waitFor({ state: 'visible' });
}

/**
 * Enable Mock Market so that ReplayControls becomes visible inside the
 * Advanced panel.  The toggle is data-testid="live-toggle-mock-market".
 */
async function enableMockMarket(page) {
  const toggle = page.getByTestId('live-toggle-mock-market');
  await toggle.waitFor({ state: 'visible' });
  await toggle.click();
  // ReplayControls renders inside the panel after this toggle
  await page.getByTestId('replay-date-input').waitFor({ state: 'visible' });
}

// ── Tests ─────────────────────────────────────────────────────────────────────

test.describe('Replay Controls — Advanced Panel', () => {

  test('Advanced panel shows replay controls after enabling Mock Market', async ({ page }) => {
    await mockReplayInactive(page);

    await openAdvancedPanel(page);
    await enableMockMarket(page);

    // Core replay UI elements must be present
    await expect(page.getByTestId('replay-date-input')).toBeVisible();
    // Speed button exists with initial default speed (30x)
    await expect(page.getByTestId('replay-speed-btn-30')).toBeVisible();
    // Play/Stop button exists
    await expect(page.getByTestId('replay-start-btn')).toBeVisible();
  });

  test('Setting date and speed then clicking Play sends POST /live-ui/replay/start', async ({ page }) => {
    await mockReplayInactive(page);
    await mockReplayStart(page);

    // Capture the start request so we can assert its body/params
    const startRequestPromise = page.waitForRequest((req) =>
      req.url().includes('/live-ui/replay/start') && req.method() === 'POST'
    );

    await openAdvancedPanel(page);
    await enableMockMarket(page);

    // Set a specific date
    const dateInput = page.getByTestId('replay-date-input');
    await dateInput.fill('2026-04-01');

    // Cycle speed: default is 30x, click once to change to 60x
    await page.getByTestId('replay-speed-btn-30').click();
    // Now the speed button shows 60x
    await expect(page.getByTestId('replay-speed-btn-60')).toBeVisible();

    // Click Play
    await page.getByTestId('replay-start-btn').click();

    const startRequest = await startRequestPromise;
    expect(startRequest.url()).toContain('date=2026-04-01');
    expect(startRequest.url()).toContain('speed=60');
  });

  test('REPLAY MODE ACTIVE banner appears after replay starts', async ({ page }) => {
    // Phase 1: status is inactive — replay not yet running
    await mockReplayInactive(page);
    await mockReplayStart(page);

    await openAdvancedPanel(page);
    await enableMockMarket(page);

    // Banner must NOT be visible before starting
    await expect(page.locator('.replay-banner')).not.toBeVisible();

    // Phase 2: after clicking Play, status flips to active
    await mockReplayActive(page);

    await page.getByTestId('replay-start-btn').click();

    // Banner must appear
    await expect(page.locator('.replay-banner')).toBeVisible();
    await expect(page.locator('.replay-banner')).toContainText('REPLAY MODE ACTIVE');
  });

  test('Virtual clock updates after polling returns active status', async ({ page }) => {
    // Start with inactive, then flip to active to simulate polling transition
    await mockReplayInactive(page);
    await mockReplayStart(page);

    await openAdvancedPanel(page);
    await enableMockMarket(page);

    // Switch status mock to active with a known virtualNow
    const knownVirtualNow = '2026-04-27T09:30:00-05:00[America/New_York]';
    await mockReplayActive(page, knownVirtualNow);

    await page.getByTestId('replay-start-btn').click();

    // Virtual clock element should appear and display a formatted date/time
    // parseIso strips the zone-id suffix and formats as DD/MM/YYYY HH:MM (UTC)
    // 09:30 ET = 14:30 UTC → expected "27/04/2026 14:30"
    const clock = page.getByTestId('replay-virtual-clock');
    await clock.waitFor({ state: 'visible' });
    await expect(clock).toContainText('27/04/2026 14:30');
  });

  test('Clicking Stop hides the REPLAY MODE ACTIVE banner', async ({ page }) => {
    // Start with an active replay detected on mount (orphan replay scenario)
    await mockReplayActive(page);
    await mockReplayStop(page);

    await page.goto('/');

    // Banner should appear because status is active from the first poll
    await expect(page.locator('.replay-banner')).toBeVisible({ timeout: 5000 });

    // Now open Advanced panel to reach the Stop button
    await openAdvancedPanel(page);
    await enableMockMarket(page);

    // Switch status mock to inactive so polling confirms stop
    await mockReplayInactive(page);

    // The button now shows the Stop icon (Square); click it
    await page.getByTestId('replay-start-btn').click();

    // Banner must disappear
    await expect(page.locator('.replay-banner')).not.toBeVisible();
  });

});
