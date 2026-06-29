const { test, expect } = require('@playwright/test');

test.describe('Today UI', () => {
  test('opens task editor by clicking task title', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const createResponse = await request.post('/api/tasks', {
      data: {
        title: 'Playwright smoke task',
        description: 'Links: https://example.com/docs',
        date: today,
        priority: 'HIGH',
        source: 'MANUAL'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const task = await createResponse.json();

    try {
      await page.goto('/ui/today');
      await expect(page).toHaveTitle(/План дня/);

      const taskLink = page.locator(`a.task-title-clickable[href="/ui/tasks/${task.id}/edit"]`);
      await expect(taskLink).toHaveText(task.title);
      await taskLink.click();

      await expect(page).toHaveURL(new RegExp(`/ui/tasks/${task.id}/edit$`));
      await expect(page.locator('#task-edit-form')).toBeVisible();
      await expect(page.locator('#task-editor-links')).toBeAttached();
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });

  test('task editor supports save and save-and-close flows', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const createResponse = await request.post('/api/tasks', {
      data: {
        title: 'Playwright edit save modes',
        description: 'Initial description',
        date: today,
        priority: 'NORMAL',
        source: 'MANUAL'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const task = await createResponse.json();

    try {
      await page.goto(`/ui/tasks/${task.id}/edit`);

      const description = page.locator('#md-input');
      await description.fill('Draft state');
      await page.getByRole('button', { name: 'Сохранить', exact: true }).click();

      await expect(page).toHaveURL(new RegExp(`/ui/tasks/${task.id}/edit$`));
      await expect(page.locator('#save-status')).toContainText('Задача сохранена');
      await expect(description).toHaveValue('Draft state');

      await description.fill('Final state');
      await page.getByRole('button', { name: 'Сохранить и закрыть' }).click();

      await expect(page).toHaveURL(/\/ui\/today$/);

      const descriptionResponse = await request.get(`/api/tasks/${task.id}/description`);
      expect(descriptionResponse.ok()).toBeTruthy();
      const payload = await descriptionResponse.json();
      expect(payload.contentMd).toBe('Final state');
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });

  test('shifts deadline by one day with the "Завтра" button', async ({ page, request }) => {
    const createResponse = await request.post('/api/tasks', {
      data: {
        title: 'Playwright deadline shift',
        description: 'Deadline shift scenario',
        date: '2026-06-26',
        priority: 'NORMAL',
        source: 'MANUAL'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const task = await createResponse.json();

    try {
      await page.goto('/ui/today');

      const row = page.locator(`.task-row[data-id="${task.id}"]`);
      await expect(row).toBeVisible();
      await expect(row.locator('.task-deadline')).toContainText('26.06.2026');

      await row.getByRole('button', { name: 'Завтра' }).click();
      await page.waitForLoadState('networkidle');

      await expect(row.locator('.task-deadline')).toContainText('27.06.2026');
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });
});
