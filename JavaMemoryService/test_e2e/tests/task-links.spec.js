const { test, expect } = require('@playwright/test');

test.describe('Task links UI', () => {
  test('links a task via autocomplete, sees mirrored link on the other task, then deletes it', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const suffix = Date.now();

    const createA = await request.post('/api/tasks', {
      data: { title: `Playwright link source ${suffix}`, date: today, priority: 'NORMAL', source: 'MANUAL' }
    });
    expect(createA.ok()).toBeTruthy();
    const taskA = await createA.json();

    const createB = await request.post('/api/tasks', {
      data: { title: `Playwright link target ${suffix}`, date: today, priority: 'NORMAL', source: 'MANUAL' }
    });
    expect(createB.ok()).toBeTruthy();
    const taskB = await createB.json();

    try {
      await page.goto(`/ui/tasks/${taskA.id}/edit`);

      const linksSection = page.locator('[data-testid="task-links"]');
      await expect(linksSection).toBeVisible();
      await expect(linksSection).toContainText('Связей пока нет.');

      await linksSection.locator('#task-link-type').selectOption('BLOCKS');
      await linksSection.locator('#task-link-search').fill(taskB.title);
      await linksSection.locator('.task-link-search-result').filter({ hasText: taskB.title }).click();

      await expect(linksSection.locator('#task-link-add-btn')).toBeEnabled();
      await linksSection.locator('#task-link-add-btn').click();
      await page.waitForLoadState('networkidle');

      await expect(page.locator('#task-links-list')).toContainText(taskB.title);
      await expect(page.locator('#task-links-list')).toContainText('BLOCKS');

      const listA = await (await request.get(`/api/tasks/${taskA.id}/links`)).json();
      expect(listA).toHaveLength(1);
      expect(listA[0].direction).toBe('OUT');
      expect(listA[0].linkType).toBe('BLOCKS');

      const listB = await (await request.get(`/api/tasks/${taskB.id}/links`)).json();
      expect(listB).toHaveLength(1);
      expect(listB[0].direction).toBe('IN');
      expect(listB[0].linkType).toBe('BLOCKED_BY');

      await page.goto(`/ui/tasks/${taskA.id}/edit`);
      const linkId = listA[0].id;
      await page.locator(`.task-link-delete-btn[data-link-id="${linkId}"]`).click();
      await page.waitForLoadState('networkidle');

      await expect(page.locator('[data-testid="task-links"]')).toContainText('Связей пока нет.');
      const afterDelete = await (await request.get(`/api/tasks/${taskA.id}/links`)).json();
      expect(afterDelete).toHaveLength(0);
    } finally {
      await request.post(`/api/tasks/${taskA.id}/archive`);
      await request.post(`/api/tasks/${taskB.id}/archive`);
    }
  });
});
