const { test, expect } = require('@playwright/test');

async function openSearchResult(page, query, title) {
  await page.goto(`/ui/search?q=${encodeURIComponent(query)}`);
  const resultLink = page.getByRole('link', { name: title, exact: true }).first();
  await expect(resultLink).toBeVisible();
  await resultLink.click();
}

test.describe('Global Search is a standalone top-level page (CR-MEM-022)', () => {
  test('/ui/search renders directly, no redirect to Agent Workspace', async ({ page }) => {
    const response = await page.goto('/ui/search');
    expect(response.request().redirectedFrom()).toBeNull();
    await expect(page.locator('h4', { hasText: 'Search LeaderOS' })).toBeVisible();
  });

  test('Agent Workspace no longer has a Search tab', async ({ page }) => {
    await page.goto('/ui/agent-workspace');
    await expect(page.locator('#workspaceTabs a', { hasText: 'Search' })).toHaveCount(0);
    await expect(page.locator('#workspaceTabs a', { hasText: 'Chat' })).toBeVisible();
    await expect(page.locator('#workspaceTabs a', { hasText: 'Console' })).toBeVisible();
  });
});

test.describe('Global Search UI', () => {
  test('opens task result in task editor', async ({ page, request }) => {
    const today = new Date().toISOString().slice(0, 10);
    const taskTitle = `PW Search Task ${Date.now()}`;
    const createResponse = await request.post('/api/tasks', {
      data: {
        title: taskTitle,
        description: 'Search task description',
        date: today,
        priority: 'HIGH',
        source: 'MANUAL'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const task = await createResponse.json();

    try {
      await openSearchResult(page, taskTitle, taskTitle);
      await expect(page).toHaveURL(new RegExp(`/ui/tasks/${task.id}/edit$`));
      await expect(page.locator('#task-edit-form')).toBeVisible();
    } finally {
      await request.delete(`/api/tasks/${task.id}`);
    }
  });

  test('opens note result in edit modal', async ({ page, request }) => {
    const title = `PW Search Note ${Date.now()}`;
    const createResponse = await request.post('/api/notes', {
      data: {
        title,
        text: 'Search note body',
        tags: 'search,e2e',
        source: 'manual-ui'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const note = await createResponse.json();

    try {
      await openSearchResult(page, title, title);
      await expect(page).toHaveURL(new RegExp(`/ui/notes\\?edit=${note.id}#note-${note.id}$`));
      await expect(page.locator(`#edit-note-${note.id}`)).toBeVisible();
    } finally {
      await request.delete(`/api/notes/${note.id}`);
    }
  });

  test('opens person result in edit modal', async ({ page, request }) => {
    const fullName = `PW Search Person ${Date.now()}`;
    const createResponse = await request.post('/api/people', {
      data: {
        fullName,
        login: `pw_${Date.now()}`,
        email: `pw-${Date.now()}@example.com`,
        domain: 'Platform'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const person = await createResponse.json();

    try {
      await openSearchResult(page, fullName, fullName);
      await expect(page).toHaveURL(new RegExp(`/ui/people\\?edit=${person.id}#person-${person.id}$`));
      await expect(page.locator(`#edit-${person.id}`)).toBeVisible();
    } finally {
      await request.delete(`/api/people/${person.id}`);
    }
  });

  test('opens risk result in edit modal', async ({ page, request }) => {
    const title = `PW Search Risk ${Date.now()}`;
    const createResponse = await request.post('/api/risks', {
      data: {
        title,
        description: 'Search risk description',
        probability: 'MEDIUM',
        impact: 'HIGH'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const risk = await createResponse.json();

    try {
      await openSearchResult(page, title, title);
      await expect(page).toHaveURL(new RegExp(`/ui/risks\\?edit=${risk.id}#risk-${risk.id}$`));
      await expect(page.locator(`#edit-${risk.id}`)).toBeVisible();
    } finally {
      await request.delete(`/api/risks/${risk.id}`);
    }
  });

  test('opens incident result in edit modal', async ({ page, request }) => {
    const title = `PW Search Incident ${Date.now()}`;
    const createResponse = await request.post('/api/incidents', {
      data: {
        title,
        severity: 'P2',
        description: 'Search incident description'
      }
    });

    expect(createResponse.ok()).toBeTruthy();
    const incident = await createResponse.json();

    try {
      await openSearchResult(page, title, title);
      await expect(page).toHaveURL(new RegExp(`/ui/incidents\\?edit=${incident.id}#incident-${incident.id}$`));
      await expect(page.locator(`#edit-${incident.id}`)).toBeVisible();
    } finally {
      await request.delete(`/api/incidents/${incident.id}`);
    }
  });
});
