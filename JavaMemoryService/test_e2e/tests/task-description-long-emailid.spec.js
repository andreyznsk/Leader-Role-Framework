const { test, expect } = require('@playwright/test');

test.describe('Task description update with long emailId (issue #89)', () => {
  test('updating description does not fail when task emailId exceeds 255 chars', async ({ request }) => {
    const suffix = Date.now();
    const longEmailId = `<${'x'.repeat(300)}-${suffix}@mail.example.com>`;

    const createResponse = await request.post('/api/tasks/pending', {
      data: {
        title: `Long emailId task ${suffix}`,
        description: 'Initial description',
        emailId: longEmailId,
        sender: 'sender@example.com',
        priority: 'NORMAL'
      }
    });
    expect(createResponse.ok()).toBeTruthy();
    const pendingTask = await createResponse.json();
    expect(pendingTask.emailId.length).toBeGreaterThan(255);

    const confirmResponse = await request.post(`/api/tasks/${pendingTask.id}/confirm`);
    expect(confirmResponse.ok()).toBeTruthy();
    const task = await confirmResponse.json();

    try {
      const descriptionResponse = await request.put(`/api/tasks/${task.id}/description`, {
        data: {
          contentMd: 'Updated description that used to trigger PSQLException on long emailId'
        }
      });

      expect(descriptionResponse.ok()).toBeTruthy();

      const timelineResponse = await request.get(`/api/tasks/${task.id}/timeline`);
      expect(timelineResponse.ok()).toBeTruthy();
      const timeline = await timelineResponse.json();

      const descriptionEvent = timeline.find(event => event.eventType === 'DESCRIPTION_UPDATED');
      expect(descriptionEvent).toBeTruthy();
      expect(descriptionEvent.sourceId.length).toBeLessThanOrEqual(255);
    } finally {
      await request.post(`/api/tasks/${task.id}/archive`);
    }
  });
});
