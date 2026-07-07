const { test, expect } = require('@playwright/test');

test.describe('Task description WYSIWYG editor (Toast UI, CR-MEM-029)', () => {
  test('renders, edits, saves and restores markdown via the WYSIWYG editor', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const suffix = Date.now();

    const createResponse = await request.post('/api/tasks', {
      data: {
        title: `Playwright toastui task ${suffix}`,
        date: today,
        priority: 'NORMAL',
        source: 'MANUAL'
      }
    });
    expect(createResponse.ok()).toBeTruthy();
    const task = await createResponse.json();

    try {
      await page.goto(`/ui/tasks/${task.id}/edit`);

      const editor = page.locator('[data-testid="task-description-editor"]');
      await expect(editor).toBeVisible();
      // Toolbar renders only once the editor instance has mounted successfully.
      await expect(editor.locator('.toastui-editor-toolbar')).toBeVisible();
      // hideModeSwitch: true — only WYSIWYG is offered, no Markdown/WYSIWYG tab switcher.
      await expect(page.locator('.toastui-editor-mode-switch')).toHaveCount(0);

      const markdown = `## Release plan ${suffix}\n\n- toast редактор item one\n- item two\n`;
      await page.evaluate((md) => {
        // eslint-disable-next-line no-undef
        descriptionEditor.setMarkdown(md);
      }, markdown);

      await page.locator('button[name="action"][value="save"]').click();
      await expect(page.locator('#save-status')).toContainText('Задача сохранена');

      const descResponse = await request.get(`/api/tasks/${task.id}/description`, {
        headers: { Accept: 'text/plain' }
      });
      const savedMarkdown = await descResponse.text();
      expect(savedMarkdown).toContain(`Release plan ${suffix}`);
      expect(savedMarkdown).toContain('item one');

      await page.reload();
      const restored = await page.evaluate(() => {
        // eslint-disable-next-line no-undef
        return descriptionEditor.getMarkdown();
      });
      expect(restored).toContain(`Release plan ${suffix}`);

      const exportResponse = await request.get(`/api/tasks/${task.id}/description/export-md`);
      expect(exportResponse.ok()).toBeTruthy();
      expect(exportResponse.headers()['content-type']).toContain('text/markdown');
      const exported = await exportResponse.text();
      expect(exported).toContain(`Release plan ${suffix}`);

      const searchResponse = await request.post('/api/search', {
        data: { query: 'toast редактор', layers: ['TASK'], mode: 'QUICK', limit: 10 }
      });
      expect(searchResponse.ok()).toBeTruthy();
      const searchResults = await searchResponse.json();
      const serialized = JSON.stringify(searchResults);
      expect(serialized).toContain(String(task.id));
    } finally {
      await request.post(`/api/tasks/${task.id}/archive`);
    }
  });
});
