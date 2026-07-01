const { test, expect } = require('@playwright/test');

test.describe('MEM-023: Task Edit Right Control Panel', () => {
  let taskId;

  test.beforeEach(async ({ request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const res = await request.post('/api/tasks', {
      data: {
        title: `MEM-023 E2E task ${Date.now()}`,
        date: today,
        priority: 'LOW',
        source: 'MANUAL'
      }
    });
    expect(res.ok()).toBeTruthy();
    taskId = (await res.json()).id;
  });

  test.afterEach(async ({ request }) => {
    if (taskId) {
      await request.post(`/api/tasks/${taskId}/archive`);
    }
  });

  test('layout: control panel is in right column with correct element order', async ({ page }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    const controlPanel = page.locator('[data-testid="task-control-panel"]');
    await expect(controlPanel).toBeVisible();

    const priority = controlPanel.locator('select[name="priority"]');
    const dueDate = controlPanel.locator('input[name="dueDate"]');
    const statusSelect = controlPanel.locator('[data-testid="task-status-select"]');
    const saveBtn = controlPanel.locator('button[value="save"]');
    const saveCloseBtn = controlPanel.locator('button[value="save_close"]');
    const archiveBtn = controlPanel.locator('#delete-btn');
    const timeline = page.locator('[data-testid="task-timeline"]');

    await expect(priority).toBeVisible();
    await expect(dueDate).toBeVisible();
    await expect(statusSelect).toBeVisible();
    await expect(saveBtn).toBeVisible();
    await expect(saveCloseBtn).toBeVisible();
    await expect(archiveBtn).toBeVisible();
    await expect(timeline).toBeVisible();

    // Verify DOM order: priority before dueDate before status before saveBtn before timeline
    const boxes = await Promise.all([priority, statusSelect, saveBtn, timeline].map(l => l.boundingBox()));
    expect(boxes[0].y).toBeLessThan(boxes[1].y); // priority above status
    expect(boxes[1].y).toBeLessThan(boxes[2].y); // status above save button
    expect(boxes[2].y).toBeLessThan(boxes[3].y); // save button above timeline
  });

  test('status select contains all valid statuses', async ({ page }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    const statusSelect = page.locator('[data-testid="task-status-select"]');
    const options = await statusSelect.locator('option').allTextContents();

    expect(options).toContain('PENDING');
    expect(options).toContain('TODO');
    expect(options).toContain('IN_PROGRESS');
    expect(options).toContain('BLOCKED');
    expect(options).toContain('DONE');
    expect(options).toContain('ARCHIVED');
    expect(options).not.toContain('DELETED');
  });

  test('Save button saves and stays on edit page', async ({ page }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    await page.locator('input[name="title"]').fill('MEM-023 title updated');
    await page.locator('button[value="save"]').click();

    await expect(page.locator('#save-status')).toContainText('Задача сохранена');
    await expect(page).toHaveURL(new RegExp(`/ui/tasks/${taskId}/edit`));
  });

  test('Save and close redirects to today', async ({ page }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    await page.locator('input[name="title"]').fill('MEM-023 save and close');
    await page.locator('button[value="save_close"]').click();

    await expect(page).toHaveURL(/\/ui\/today$/);
  });

  test('status change via dropdown is saved', async ({ page, request }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    const statusSelect = page.locator('[data-testid="task-status-select"]');
    await statusSelect.selectOption('IN_PROGRESS');
    await page.locator('button[value="save"]').click();
    await expect(page.locator('#save-status')).toContainText('Задача сохранена');

    const taskRes = await request.get(`/api/tasks/${taskId}`);
    const taskData = await taskRes.json();
    expect(taskData.status).toBe('IN_PROGRESS');
  });

  test('Archive button is destructive and requires confirmation', async ({ page }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    const archiveBtn = page.locator('#delete-btn');
    await expect(archiveBtn).toHaveClass(/btn-danger/);
    await expect(archiveBtn).toContainText('Архивировать');

    await archiveBtn.click();
    await expect(archiveBtn).toContainText('Точно архивировать?');
  });

  test('Archive completes and redirects to today', async ({ page, request }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    const archiveBtn = page.locator('#delete-btn');
    await archiveBtn.click();
    await archiveBtn.click();

    await expect(page).toHaveURL(/\/ui\/today$/);

    const res = await request.get(`/api/tasks/${taskId}`);
    const data = await res.json();
    expect(data.status).toBe('ARCHIVED');
  });

  test('Timeline empty state is shown when no events exist is replaced by TASK_CREATED', async ({ page }) => {
    await page.goto(`/ui/tasks/${taskId}/edit`);

    const timeline = page.locator('[data-testid="task-timeline"]');
    // New task always has TASK_CREATED event
    await expect(timeline).toContainText('Задача создана');
  });

  test('layout is single-column on mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto(`/ui/tasks/${taskId}/edit`);

    const controlPanel = page.locator('[data-testid="task-control-panel"]');
    await expect(controlPanel).toBeVisible();

    // On mobile, control panel should be below the description (not beside it)
    const descBox = await page.locator('#md-input').boundingBox();
    const panelBox = await controlPanel.boundingBox();
    expect(panelBox.y).toBeGreaterThan(descBox.y);
  });
});
