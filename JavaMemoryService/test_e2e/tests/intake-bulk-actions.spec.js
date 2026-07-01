const { test, expect } = require('@playwright/test');

async function createIntakeItem(request, overrides = {}) {
  const runId = 'intake-bulk-' + Date.now() + '-' + Math.floor(Math.random() * 10000);
  const response = await request.post('/api/intake', {
    data: {
      sourceType: 'MANUAL',
      sourceId: runId,
      sourcePayload: { text: runId + ' payload' },
      suggestedRoute: 'NOTE',
      suggestedPayload: { title: runId + ' title', text: runId + ' body' },
      confidence: 0.9,
      ...overrides
    }
  });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

test.describe('Intake Gateway bulk checkbox actions', () => {
  test('bulk reject marks selected items REJECTED and leaves others untouched', async ({ page, request }) => {
    const toReject1 = await createIntakeItem(request);
    const toReject2 = await createIntakeItem(request);
    const untouched = await createIntakeItem(request);

    await page.goto('/ui/intake');

    await page.locator(`.intake-item-checkbox[data-item-id="${toReject1.id}"]`).check();
    await page.locator(`.intake-item-checkbox[data-item-id="${toReject2.id}"]`).check();

    const rejectBtn = page.locator('#intake-bulk-reject-btn');
    await expect(rejectBtn).toBeEnabled();
    await expect(page.locator('#intake-bulk-count')).toHaveText('2 выбрано');

    page.once('dialog', (dialog) => dialog.accept());
    await rejectBtn.click();
    await page.waitForLoadState('networkidle');

    const r1 = await request.get(`/api/intake/${toReject1.id}`);
    const r2 = await request.get(`/api/intake/${toReject2.id}`);
    const r3 = await request.get(`/api/intake/${untouched.id}`);
    expect((await r1.json()).status).toBe('REJECTED');
    expect((await r2.json()).status).toBe('REJECTED');
    expect((await r3.json()).status).toBe('NEW');
  });

  test('bulk apply confirms selected items with their current route', async ({ page, request }) => {
    const item1 = await createIntakeItem(request);
    const item2 = await createIntakeItem(request);

    await page.goto('/ui/intake');

    await page.locator(`.intake-item-checkbox[data-item-id="${item1.id}"]`).check();
    await page.locator(`.intake-item-checkbox[data-item-id="${item2.id}"]`).check();

    const applyBtn = page.locator('#intake-bulk-apply-btn');
    await expect(applyBtn).toBeEnabled();

    page.once('dialog', (dialog) => dialog.accept());
    await applyBtn.click();
    await page.waitForLoadState('networkidle');

    const r1 = await request.get(`/api/intake/${item1.id}`);
    const r2 = await request.get(`/api/intake/${item2.id}`);
    const body1 = await r1.json();
    const body2 = await r2.json();
    expect(body1.status).toBe('APPLIED');
    expect(body1.finalRoute).toBe('NOTE');
    expect(body2.status).toBe('APPLIED');
    expect(body2.finalRoute).toBe('NOTE');
  });

  test('dismissing the bulk reject confirm leaves items unchanged', async ({ page, request }) => {
    const item = await createIntakeItem(request);

    await page.goto('/ui/intake');
    await page.locator(`.intake-item-checkbox[data-item-id="${item.id}"]`).check();

    page.once('dialog', (dialog) => dialog.dismiss());
    await page.locator('#intake-bulk-reject-btn').click();
    await page.waitForTimeout(200);

    const r = await request.get(`/api/intake/${item.id}`);
    expect((await r.json()).status).toBe('NEW');
  });

  test('bulk action buttons are disabled until an item is selected', async ({ page, request }) => {
    await createIntakeItem(request);

    await page.goto('/ui/intake');

    await expect(page.locator('#intake-bulk-apply-btn')).toBeDisabled();
    await expect(page.locator('#intake-bulk-reject-btn')).toBeDisabled();
    await expect(page.locator('#intake-bulk-count')).toHaveText('0 выбрано');
  });

  test('"select all" checkbox selects every visible item checkbox', async ({ page, request }) => {
    const itemA = await createIntakeItem(request);
    const itemB = await createIntakeItem(request);

    await page.goto('/ui/intake');
    await page.locator('#intake-select-all').check();

    await expect(page.locator(`.intake-item-checkbox[data-item-id="${itemA.id}"]`)).toBeChecked();
    await expect(page.locator(`.intake-item-checkbox[data-item-id="${itemB.id}"]`)).toBeChecked();
    await expect(page.locator('#intake-bulk-apply-btn')).toBeEnabled();
  });
});
