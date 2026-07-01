const { test, expect } = require('@playwright/test');

async function createTask(request, overrides = {}) {
  const today = new Date().toISOString().slice(0, 10);
  const response = await request.post('/api/tasks', {
    data: {
      title: 'Playwright done-confirm task',
      description: 'Done confirmation scenario',
      date: today,
      priority: 'NORMAL',
      source: 'MANUAL',
      ...overrides
    }
  });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

test.describe('DONE transition requires confirmation', () => {
  test('today checkbox: dismissing the confirm keeps the task open', async ({ page, request }) => {
    const task = await createTask(request);

    try {
      await page.goto('/ui/today');

      const row = page.locator(`.task-row[data-id="${task.id}"]`);
      await expect(row).toBeVisible();

      let dialogSeen = false;
      page.once('dialog', async (dialog) => {
        dialogSeen = true;
        expect(dialog.type()).toBe('confirm');
        await dialog.dismiss();
      });

      await row.locator('button[onclick="toggleDone(this)"]').click();
      await page.waitForTimeout(200);

      expect(dialogSeen).toBe(true);

      const statusResponse = await request.get(`/api/tasks/${task.id}`);
      expect(statusResponse.ok()).toBeTruthy();
      const statusPayload = await statusResponse.json();
      expect(statusPayload.status).not.toBe('DONE');
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });

  test('today checkbox: accepting the confirm marks the task DONE', async ({ page, request }) => {
    const task = await createTask(request);

    try {
      await page.goto('/ui/today');

      const row = page.locator(`.task-row[data-id="${task.id}"]`);
      await expect(row).toBeVisible();

      page.once('dialog', (dialog) => dialog.accept());

      await row.locator('button[onclick="toggleDone(this)"]').click();
      await page.waitForLoadState('networkidle');

      const statusResponse = await request.get(`/api/tasks/${task.id}`);
      expect(statusResponse.ok()).toBeTruthy();
      const statusPayload = await statusResponse.json();
      expect(statusPayload.status).toBe('DONE');
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });

  test('task editor: dismissing the confirm keeps the previous status on save', async ({ page, request }) => {
    const task = await createTask(request);

    try {
      await page.goto(`/ui/tasks/${task.id}/edit`);

      await page.locator('[data-testid="task-status-select"]').selectOption('DONE');

      page.once('dialog', async (dialog) => {
        expect(dialog.type()).toBe('confirm');
        await dialog.dismiss();
      });

      await page.locator('button[value="save"]').click();
      await page.waitForTimeout(200);

      const statusResponse = await request.get(`/api/tasks/${task.id}`);
      expect(statusResponse.ok()).toBeTruthy();
      const statusPayload = await statusResponse.json();
      expect(statusPayload.status).not.toBe('DONE');
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });

  test('task editor: accepting the confirm saves status DONE', async ({ page, request }) => {
    const task = await createTask(request);

    try {
      await page.goto(`/ui/tasks/${task.id}/edit`);

      await page.locator('[data-testid="task-status-select"]').selectOption('DONE');

      page.once('dialog', (dialog) => dialog.accept());

      await page.locator('button[value="save"]').click();
      await expect(page.locator('#save-status')).toContainText('Задача сохранена');

      const statusResponse = await request.get(`/api/tasks/${task.id}`);
      expect(statusResponse.ok()).toBeTruthy();
      const statusPayload = await statusResponse.json();
      expect(statusPayload.status).toBe('DONE');
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });

  test('task editor: no confirmation prompt when saving without changing status', async ({ page, request }) => {
    const task = await createTask(request);

    try {
      await page.goto(`/ui/tasks/${task.id}/edit`);

      let dialogSeen = false;
      page.once('dialog', async (dialog) => {
        dialogSeen = true;
        await dialog.dismiss();
      });

      await page.locator('button[value="save"]').click();
      await expect(page.locator('#save-status')).toContainText('Задача сохранена');

      expect(dialogSeen).toBe(false);
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });
});
