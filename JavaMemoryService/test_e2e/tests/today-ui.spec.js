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
      await expect(page).toHaveTitle(/ToDo/);

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
      await page.locator('button[value="save"]').click();

      await expect(page).toHaveURL(new RegExp(`/ui/tasks/${task.id}/edit$`));
      await expect(page.locator('#save-status')).toContainText('Задача сохранена');
      await expect(description).toHaveValue('Draft state');

      await description.fill('Final state');
      await page.locator('button[value="save_close"]').click();

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

  test('links pending mail candidate to an alternative target task from today UI', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const suffix = Date.now();

    const suggestedResponse = await request.post('/api/tasks', {
      data: {
        title: `Suggested target ${suffix}`,
        description: 'Suggested task description',
        date: today,
        priority: 'NORMAL',
        source: 'MANUAL'
      }
    });
    expect(suggestedResponse.ok()).toBeTruthy();
    const suggestedTask = await suggestedResponse.json();

    const alternativeResponse = await request.post('/api/tasks', {
      data: {
        title: `Alternative target ${suffix}`,
        description: 'Alternative task description',
        date: today,
        priority: 'HIGH',
        source: 'MANUAL'
      }
    });
    expect(alternativeResponse.ok()).toBeTruthy();
    const alternativeTask = await alternativeResponse.json();

    const pendingResponse = await request.post('/api/tasks/pending', {
      data: {
        title: `Pending link candidate ${suffix}`,
        description: 'Mail candidate for linking',
        emailId: `msg-link-${suffix}`,
        sender: 'sender@test.com',
        priority: 'NORMAL',
        pendingType: 'LINK_TO_TASK',
        suggestedTaskId: suggestedTask.id,
        agentConfidence: 0.91,
        agentReason: 'Same release thread',
        sourceType: 'EMAIL',
        sourceSubject: 'RE: Release',
        sourceSender: 'sender@test.com'
      }
    });
    expect(pendingResponse.ok()).toBeTruthy();
    const pendingTask = await pendingResponse.json();

    try {
      await page.goto('/ui/today');

      const pendingCard = page.locator('.los-pending-card').filter({ hasText: pendingTask.title });
      await expect(pendingCard).toBeVisible();
      await expect(pendingCard).toContainText('LINK_TO_TASK');

      await pendingCard.getByRole('button', { name: /Целевая задача/i }).click();
      const pickerModal = page.locator('#taskSearchPickerModal');
      await expect(pickerModal).toBeVisible();

      const searchInput = pickerModal.locator('#taskSearchPickerInput');
      await searchInput.fill(alternativeTask.title);
      await pickerModal.locator('.task-search-picker-result').filter({ hasText: alternativeTask.title }).click();

      await expect(pendingCard.locator(`#target-task-label-${pendingTask.id}`)).toContainText(`TASK-${alternativeTask.id}`);

      await pendingCard.getByRole('button', { name: 'Связать с задачей' }).click();
      await page.waitForLoadState('networkidle');

      await expect(page.locator('.los-pending-card').filter({ hasText: pendingTask.title })).toHaveCount(0);

      const timelineResponse = await request.get(`/api/tasks/${alternativeTask.id}/timeline`);
      expect(timelineResponse.ok()).toBeTruthy();
      const timeline = await timelineResponse.json();
      expect(timeline.map(event => event.eventType)).toContain('EMAIL_LINKED');
      expect(JSON.stringify(timeline)).toContain(`"pendingTaskId":${pendingTask.id}`);
    } finally {
      await request.post(`/api/tasks/${pendingTask.id}/archive`);
      await request.delete(`/api/tasks/${suggestedTask.id}`);
      await request.delete(`/api/tasks/${alternativeTask.id}`);
    }
  });
});
