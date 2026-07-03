const { test, expect } = require('@playwright/test');

// CR-MEM-028: live badge polling. The layout script polls GET /api/ui/badges
// every 10s and toggles [data-badge] elements without a page reload.
const POLL_INTERVAL_MS = 10_000;

test.describe('Live badge polling (CR-MEM-028)', () => {
  test('sidebar badges appear after the first poll and reflect counts', async ({ page }) => {
    let badgeCalls = 0;
    await page.route('**/api/ui/badges', (route) => {
      badgeCalls++;
      route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          counts: { newIntake: 4, pendingTasks: 2 },
          serverTime: new Date().toISOString()
        })
      });
    });

    await page.goto('/ui/today');

    const intakeBadge = page.locator('[data-badge="newIntake"]');
    const todoBadge = page.locator('[data-badge="pendingTasks"]');

    // Server-rendered state: badges start hidden regardless of live counts
    // (real counts are only known after the first poll fires).
    await expect(intakeBadge).toBeHidden();

    await page.waitForTimeout(POLL_INTERVAL_MS + 1_000);

    await expect(intakeBadge).toBeVisible();
    await expect(intakeBadge).toHaveText('4');
    await expect(todoBadge).toBeVisible();
    await expect(todoBadge).toHaveText('2');
    expect(badgeCalls).toBeGreaterThanOrEqual(1);
  });

  test('badges hide again once counts drop to zero on a later poll', async ({ page }) => {
    let counts = { newIntake: 1, pendingTasks: 0 };
    await page.route('**/api/ui/badges', (route) => {
      route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({ counts, serverTime: new Date().toISOString() })
      });
    });

    await page.goto('/ui/today');
    const intakeBadge = page.locator('[data-badge="newIntake"]');

    await page.waitForTimeout(POLL_INTERVAL_MS + 1_000);
    await expect(intakeBadge).toBeVisible();
    await expect(intakeBadge).toHaveText('1');

    counts = { newIntake: 0, pendingTasks: 0 };
    await page.waitForTimeout(POLL_INTERVAL_MS + 1_000);
    await expect(intakeBadge).toBeHidden();
  });

  test('poller stops while the tab is hidden and refreshes immediately on return', async ({ page }) => {
    let badgeCalls = 0;
    await page.route('**/api/ui/badges', (route) => {
      badgeCalls++;
      route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          counts: { newIntake: 3, pendingTasks: 1 },
          serverTime: new Date().toISOString()
        })
      });
    });

    await page.goto('/ui/today');
    await page.waitForTimeout(POLL_INTERVAL_MS + 1_000);
    const callsAfterFirstPoll = badgeCalls;
    expect(callsAfterFirstPoll).toBeGreaterThanOrEqual(1);

    // Simulate the tab going to the background: no fetches should fire.
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { value: true, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await page.waitForTimeout(POLL_INTERVAL_MS + 1_000);
    expect(badgeCalls).toBe(callsAfterFirstPoll);

    // Returning to the tab triggers an immediate refresh.
    await page.evaluate(() => {
      Object.defineProperty(document, 'hidden', { value: false, configurable: true });
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await expect.poll(() => badgeCalls).toBeGreaterThan(callsAfterFirstPoll);
  });
});
