const { test, expect } = require('@playwright/test');

test.describe('Today UI — ToDo / Done tabs (replaces CR-MEM-014 hide-done toggle)', () => {
  let todoTask, doneTask;

  test.beforeEach(async ({ request }) => {
    const today = new Date().toISOString().slice(0, 10);

    const r1 = await request.post('/api/tasks', {
      data: { title: 'MEM014 TODO task', date: today, priority: 'HIGH', source: 'MANUAL' }
    });
    expect(r1.ok()).toBeTruthy();
    todoTask = await r1.json();

    const r2 = await request.post('/api/tasks', {
      data: { title: 'MEM014 DONE task', date: today, priority: 'HIGH', source: 'MANUAL' }
    });
    expect(r2.ok()).toBeTruthy();
    doneTask = await r2.json();

    const r3 = await request.post(`/api/tasks/${doneTask.id}/done`);
    expect(r3.ok()).toBeTruthy();
  });

  test.afterEach(async ({ request }) => {
    if (todoTask) await request.delete(`/api/tasks/${todoTask.id}`);
    if (doneTask) await request.delete(`/api/tasks/${doneTask.id}`);
  });

  test('ToDo tab (/ui/today) always hides DONE tasks, no toggle present', async ({ page }) => {
    await page.goto('/ui/today');
    await expect(page).toHaveTitle(/ToDo/);

    await expect(page.locator(`.task-row[data-id="${todoTask.id}"]`)).toBeVisible();
    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).not.toBeAttached();
    await expect(page.locator('#hideDoneToggle')).toHaveCount(0);
  });

  test('Done tab (/ui/today?status=DONE) shows only DONE tasks', async ({ page }) => {
    await page.goto('/ui/today?status=DONE');
    await expect(page).toHaveTitle(/Done/);

    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).toBeVisible();
    await expect(page.locator(`.task-row[data-id="${todoTask.id}"]`)).not.toBeAttached();
  });

  test('sidebar has separate ToDo and Done nav items with correct active state', async ({ page }) => {
    await page.goto('/ui/today');
    const todoLink = page.locator('.los-nav-item[href="/ui/today"]');
    const doneLink = page.locator('.los-nav-item[href="/ui/today?status=DONE"]');
    await expect(todoLink).toHaveClass(/active/);
    await expect(doneLink).not.toHaveClass(/active/);

    await doneLink.click();
    await page.waitForLoadState('networkidle');
    await expect(page).toHaveURL(/status=DONE/);
    await expect(page.locator('.los-nav-item[href="/ui/today"]')).not.toHaveClass(/active/);
    await expect(page.locator('.los-nav-item[href="/ui/today?status=DONE"]')).toHaveClass(/active/);
  });

  test('reset link on Done tab stays on Done tab', async ({ page }) => {
    await page.goto('/ui/today?status=DONE&priority=HIGH');

    await page.getByRole('link', { name: 'Сбросить' }).click();
    await page.waitForLoadState('networkidle');

    await expect(page).toHaveURL(/status=DONE/);
    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).toBeVisible();
  });

  test('priority filter works together with the ToDo tab', async ({ page }) => {
    const today = new Date().toISOString().slice(0, 10);
    const r = await page.request.post('/api/tasks', {
      data: { title: 'MEM014 TODO LOW task', date: today, priority: 'LOW', source: 'MANUAL' }
    });
    expect(r.ok()).toBeTruthy();
    const todoLowTask = await r.json();

    try {
      await page.goto('/ui/today?priority=HIGH');

      await expect(page.locator(`.task-row[data-id="${todoTask.id}"]`)).toBeVisible();
      await expect(page.locator(`.task-row[data-id="${todoLowTask.id}"]`)).not.toBeAttached();
      await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).not.toBeAttached();
    } finally {
      await page.request.delete(`/api/tasks/${todoLowTask.id}`);
    }
  });
});
