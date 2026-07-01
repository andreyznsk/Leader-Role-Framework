const { test, expect } = require('@playwright/test');

const MAIN_PAGES = [
  'today', 'notes', 'captures', 'knowledge', 'settings',
  'stats', 'agent-workspace', 'people', 'risks', 'incidents', 'intake'
];

test.describe('Left sidebar navigation (CR-MEM-022)', () => {
  test.beforeEach(async ({ page }) => {
    // Clear any leftover collapse state from earlier tests. Done as a one-off
    // page.evaluate (not addInitScript) so it doesn't re-fire on every
    // in-test navigation and wipe out state a test just set.
    await page.goto('/ui/today');
    await page.evaluate(() => window.localStorage.removeItem('leaderos.sidebar.collapsed'));
  });

  for (const path of MAIN_PAGES) {
    test(`/ui/${path} renders the shared sidebar`, async ({ page }) => {
      await page.goto(`/ui/${path}`);
      await expect(page.locator('nav[data-testid="leaderos-sidebar"]')).toBeVisible();
    });
  }

  test('active nav item is highlighted by current URL', async ({ page }) => {
    await page.goto('/ui/risks');
    const activeItem = page.locator('.los-sidebar .los-nav-item.active');
    await expect(activeItem).toHaveCount(1);
    await expect(activeItem).toContainText('Risks');
  });

  test('collapsing the sidebar persists across navigation via localStorage', async ({ page }) => {
    await page.goto('/ui/today');
    const html = page.locator('html');
    await expect(html).not.toHaveClass(/los-collapsed/);

    await page.click('#los-collapse-btn');
    await expect(html).toHaveClass(/los-collapsed/);
    const stored = await page.evaluate(() => window.localStorage.getItem('leaderos.sidebar.collapsed'));
    expect(stored).toBe('true');

    await page.goto('/ui/notes');
    await expect(html).toHaveClass(/los-collapsed/);

    await page.click('#los-collapse-btn');
    await expect(html).not.toHaveClass(/los-collapsed/);
    const storedAfterExpand = await page.evaluate(() => window.localStorage.getItem('leaderos.sidebar.collapsed'));
    expect(storedAfterExpand).toBe('false');
  });

  test('mobile viewport hides sidebar behind a drawer toggle', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/ui/today');

    const sidebar = page.locator('nav[data-testid="leaderos-sidebar"]');
    await expect(sidebar).not.toBeInViewport();

    await page.click('#los-mobile-toggle');
    await expect(page.locator('html')).toHaveClass(/los-mobile-open/);
    await expect(sidebar).toBeInViewport();

    await page.click('.los-sidebar .los-nav-item[href="/ui/notes"]');
    await expect(page).toHaveURL(/\/ui\/notes$/);
    await expect(page.locator('html')).not.toHaveClass(/los-mobile-open/);
  });
});
