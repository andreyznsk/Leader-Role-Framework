const { test, expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');
const os = require('os');

test.describe('Task attachments UI', () => {
  test('uploads a file, adds a link, and deletes an attachment from the editor', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const suffix = Date.now();

    const createResponse = await request.post('/api/tasks', {
      data: {
        title: `Playwright attachments task ${suffix}`,
        date: today,
        priority: 'NORMAL',
        source: 'MANUAL'
      }
    });
    expect(createResponse.ok()).toBeTruthy();
    const task = await createResponse.json();

    const filePath = path.join(os.tmpdir(), `e2e-attachment-${suffix}.png`);
    fs.writeFileSync(filePath, Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));

    try {
      await page.goto(`/ui/tasks/${task.id}/edit`);

      const attachmentsSection = page.locator('[data-testid="task-attachments"]');
      await expect(attachmentsSection).toBeVisible();
      await expect(attachmentsSection).toContainText('Вложений пока нет.');

      await page.setInputFiles('#attachment-file-input', filePath);
      await page.locator('#attachment-upload-btn').click();
      await page.waitForLoadState('networkidle');

      await expect(page.locator('#task-attachments-list')).toContainText(`e2e-attachment-${suffix}.png`);

      await page.locator('#attachment-link-title').fill('Design doc');
      await page.locator('#attachment-link-url').fill('https://drive.google.com/file/d/abc123');
      await page.locator('#attachment-link-btn').click();
      await page.waitForLoadState('networkidle');

      await expect(page.locator('#task-attachments-list')).toContainText('Design doc');

      const listResponse = await request.get(`/api/tasks/${task.id}/attachments`);
      const attachments = await listResponse.json();
      expect(attachments).toHaveLength(2);

      const fileAttachment = attachments.find(a => a.kind === 'FILE');
      const deleteButton = page.locator(`.attachment-delete-btn[data-attachment-id="${fileAttachment.id}"]`);
      await deleteButton.click();
      await page.waitForLoadState('networkidle');

      await expect(page.locator('#task-attachments-list')).not.toContainText(`e2e-attachment-${suffix}.png`);
      const afterDeleteResponse = await request.get(`/api/tasks/${task.id}/attachments`);
      expect(await afterDeleteResponse.json()).toHaveLength(1);
    } finally {
      fs.unlinkSync(filePath);
      await request.post(`/api/tasks/${task.id}/archive`);
    }
  });
});
