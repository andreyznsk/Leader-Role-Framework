const { test, expect } = require('@playwright/test');

test.describe('Task timeline UI', () => {
  test('shows timeline, allows comment, and archives task from editor', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const suffix = Date.now();

    const createResponse = await request.post('/api/tasks', {
      data: {
        title: `Playwright timeline task ${suffix}`,
        description: 'Initial timeline description',
        date: today,
        priority: 'HIGH',
        source: 'MANUAL'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const task = await createResponse.json();

    try {
      await page.goto(`/ui/tasks/${task.id}/edit`);

      await expect(page.getByRole('heading', { name: /Задача #/ })).toBeVisible();

      const controlPanel = page.locator('[data-testid="task-control-panel"]');
      await expect(controlPanel).toBeVisible();

      const timelineSection = page.locator('[data-testid="task-timeline"]');
      await expect(timelineSection).toBeVisible();
      await expect(timelineSection).toContainText('Задача создана');

      await page.locator('#md-input').fill('Updated from Playwright timeline test');
      await page.locator('button[value="save"]').click();

      await expect(page.locator('#save-status')).toContainText('Задача сохранена');
      await page.reload();
      await expect(page.locator('[data-testid="task-timeline"]')).toContainText('Описание обновлено');

      await page.locator('#timeline-comment').fill('Playwright timeline comment');
      await page.getByRole('button', { name: 'Добавить' }).click();

      await page.waitForLoadState('networkidle');
      await expect(page.locator('[data-testid="task-timeline"]')).toContainText('Комментарий добавлен');

      for (let i = 0; i < 5; i += 1) {
        await page.locator('#timeline-comment').fill(`Playwright extra timeline comment ${i}`);
        await page.getByRole('button', { name: 'Добавить' }).click();
        await page.waitForLoadState('networkidle');
      }

      const timelineList = page.locator('#task-timeline-list');
      await expect(timelineList).toBeVisible();
      await expect(timelineList).toHaveCSS('overflow-y', 'auto');
      await expect(page.locator('#task-timeline-list .border-bottom')).toHaveCount(5);
      await expect(page.locator('[data-testid="task-timeline"] .justify-content-between .text-muted.small')).toContainText('Последние 5');

      const archiveButton = page.locator('#delete-btn');
      await expect(archiveButton).toHaveText(/Архивировать/);
      await archiveButton.click();
      await expect(archiveButton).toHaveText('Точно архивировать?');
      await archiveButton.click();

      await expect(page).toHaveURL(/\/ui\/today$/);
      await expect(page.locator(`.task-row[data-id="${task.id}"]`)).toHaveCount(0);

      const timelineResponse = await request.get(`/api/tasks/${task.id}/timeline`);
      expect(timelineResponse.ok()).toBeTruthy();
      const timeline = await timelineResponse.json();
      expect(timeline.map(event => event.eventType)).toEqual(
        expect.arrayContaining([
          'TASK_CREATED',
          'DESCRIPTION_UPDATED',
          'COMMENT_ADDED',
          'TASK_ARCHIVED'
        ])
      );
    } finally {
      await request.post(`/api/tasks/${task.id}/archive`);
    }
  });
});
