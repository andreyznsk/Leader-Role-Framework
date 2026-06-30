const { test, expect } = require('@playwright/test');

test.describe('CR-MEM-014: Today UI — Hide Done filter', () => {
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

  test('hides DONE tasks by default (hideDone=true)', async ({ page }) => {
    await page.goto('/ui/today');
    await expect(page).toHaveTitle(/План дня/);

    await expect(page.locator(`.task-row[data-id="${todoTask.id}"]`)).toBeVisible();
    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).not.toBeAttached();
  });

  test('shows DONE tasks when hideDone=false via query param', async ({ page }) => {
    await page.goto('/ui/today?hideDone=false');

    await expect(page.locator(`.task-row[data-id="${todoTask.id}"]`)).toBeVisible();
    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).toBeVisible();
  });

  test('shows DONE tasks when status=DONE filter is selected (even with hideDone default)', async ({ page }) => {
    await page.goto('/ui/today?status=DONE');

    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).toBeVisible();
    await expect(page.locator(`.task-row[data-id="${todoTask.id}"]`)).not.toBeAttached();
  });

  test('hideDone toggle checkbox is present and checked by default', async ({ page }) => {
    await page.goto('/ui/today');

    const toggle = page.locator('#hideDoneToggle');
    await expect(toggle).toBeVisible();
    await expect(toggle).toBeChecked();
  });

  test('clicking toggle shows DONE tasks', async ({ page }) => {
    await page.goto('/ui/today');

    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).not.toBeAttached();

    await page.locator('#hideDoneToggle').click();
    await page.waitForLoadState('networkidle');

    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).toBeVisible();
    await expect(page.locator('#hideDoneToggle')).not.toBeChecked();
  });

  test('reset link restores default hide-done state', async ({ page }) => {
    await page.goto('/ui/today?hideDone=false');
    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).toBeVisible();

    await page.getByRole('link', { name: 'Сбросить' }).click();
    await page.waitForLoadState('networkidle');

    await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).not.toBeAttached();
    await expect(page.locator('#hideDoneToggle')).toBeChecked();
  });

  test('priority filter works together with hideDone', async ({ page }) => {
    const today = new Date().toISOString().slice(0, 10);
    const r = await page.request.post('/api/tasks', {
      data: { title: 'MEM014 DONE LOW task', date: today, priority: 'LOW', source: 'MANUAL' }
    });
    expect(r.ok()).toBeTruthy();
    const doneLowTask = await r.json();
    await page.request.post(`/api/tasks/${doneLowTask.id}/done`);

    try {
      await page.goto('/ui/today?priority=HIGH&hideDone=false');

      await expect(page.locator(`.task-row[data-id="${doneTask.id}"]`)).toBeVisible();
      await expect(page.locator(`.task-row[data-id="${todoTask.id}"]`)).toBeVisible();
      await expect(page.locator(`.task-row[data-id="${doneLowTask.id}"]`)).not.toBeAttached();
    } finally {
      await page.request.delete(`/api/tasks/${doneLowTask.id}`);
    }
  });
});
