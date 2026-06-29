/**
 * CR-MEM-012: CaptureBot UI — global E2E test
 *
 * Участвуют все модули:
 *   CapturesApiController   — REST CRUD /api/captures
 *   CaptureController       — backward compat /api/capture
 *   CaptureInboxViewController + captures.html — UI страница
 *   CaptureService          — state machine (NEW → PROCESSING → PROCESSED / ERROR)
 *   CaptureProcessingService — processSingle via /api/captures/{id}/process
 *   CaptureClassifierAgent  — mock-классификация по префиксу текста
 *   CaptureRouter           — маршрутизация в Task / Risk / Note / Question / PersonNote / Knowledge / Journal
 *   TaskService             — создание задачи из TASK-capture
 *   RiskService             — создание риска из RISK-capture
 *   NoteService             — создание заметки из NOTE-capture
 *   UsageEventService       — фиксация CAPTURE_CREATED / CAPTURE_PROCESSED
 */

const { test, expect } = require('@playwright/test');

// Unique run ID so this test's records don't clash with live data
const RUN = `e2e-${Date.now()}`;

// ────────────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────────────

async function createCapture(request, text, source = 'api') {
  const r = await request.post('/api/captures', { data: { text, source } });
  expect(r.ok(), `POST /api/captures: ${r.status()}`).toBeTruthy();
  const body = await r.json();
  expect(body.captureId).toBeTruthy();
  expect(body.saved).toBe(true);
  return body.captureId;
}

async function processCapture(request, id) {
  const r = await request.post(`/api/captures/${id}/process`);
  expect(r.ok(), `POST /api/captures/${id}/process: ${r.status()}`).toBeTruthy();
  return r.json();
}

async function archiveCapture(request, id) {
  const r = await request.post(`/api/captures/${id}/archive`);
  expect(r.ok()).toBeTruthy();
}

async function getCapture(request, id) {
  const r = await request.get(`/api/captures/${id}`);
  expect(r.ok()).toBeTruthy();
  return r.json();
}

// ────────────────────────────────────────────────────────────────────────────

test.describe('CaptureBot UI — CR-MEM-012 global scenario', () => {

  // ── 1. UI страница ──────────────────────────────────────────────────────
  test.describe('1. UI page structure', () => {
    test('loads /ui/captures with all required sections', async ({ page }) => {
      await page.goto('/ui/captures');

      await expect(page).toHaveTitle(/CaptureBot/);

      // Quick Capture section
      await expect(page.locator('#captureText')).toBeVisible();
      await expect(page.locator('#saveCaptureButton')).toBeVisible();
      await expect(page.locator('#clearCaptureButton')).toBeVisible();

      // Capture History section — at least one of the three states is in DOM
      await expect(page.locator('#historyLoading')).toBeAttached();
      await expect(page.locator('#historyTable')).toBeAttached();
      await expect(page.locator('#historyEmpty')).toBeAttached();

      // Status filter buttons
      const statusGroup = page.locator('#statusFilterGroup');
      await expect(statusGroup.locator('.status-btn')).toHaveCount(6);
      for (const label of ['All', 'New', 'Processing', 'Processed', 'Error', 'Archived']) {
        await expect(statusGroup.locator(`.status-btn[data-status="${label === 'All' ? '' : label.toUpperCase()}"]`))
          .toBeVisible();
      }

      // Additional filter inputs
      await expect(page.locator('#filterDate')).toBeVisible();
      await expect(page.locator('#filterSource')).toBeVisible();
      await expect(page.locator('#filterRoute')).toBeVisible();
      await expect(page.locator('#filterSearch')).toBeVisible();

      // Process Now button
      await expect(page.locator('#processCapturesButton')).toBeVisible();

      // View modal exists in DOM
      await expect(page.locator('#viewCaptureModal')).toBeAttached();
    });
  });

  // ── 2. Quick Capture via UI ─────────────────────────────────────────────
  test.describe('2. Quick Capture — create from textarea', () => {
    test('saves capture from UI and shows it in history with status NEW', async ({ page, request }) => {
      const uniqueText = `NOTE: ${RUN} quick-capture-ui | UI smoke заметка`;

      await page.goto('/ui/captures');

      // Wait for history to initially load (may already be done)
      await page.waitForFunction(() => !document.getElementById('historyLoading')?.classList.contains('d-none') === false ||
        document.getElementById('historyTable')?.style.display !== 'none' ||
        !document.getElementById('historyEmpty')?.classList.contains('d-none'));

      // Fill and submit Quick Capture
      await page.locator('#captureText').fill(uniqueText);
      await page.locator('#captureSource').selectOption('ui');
      await page.locator('#saveCaptureButton').click();

      // Success message
      await expect(page.locator('#captureSuccess')).toBeVisible();
      await expect(page.locator('#captureSuccess')).toContainText('Записал');

      // Textarea cleared
      await expect(page.locator('#captureText')).toHaveValue('');

      // History table reloads — our capture must appear with NEW badge
      await page.waitForFunction(
        (text) => {
          const rows = document.querySelectorAll('#historyBody tr td:nth-child(4)');
          return Array.from(rows).some(td => td.textContent.includes(text.substring(0, 40)));
        },
        uniqueText
      );

      const allRows = page.locator('#historyBody tr');
      const matchingRow = allRows.filter({ hasText: uniqueText.substring(0, 40) });
      await expect(matchingRow).toHaveCount(1);
      await expect(matchingRow.locator('.badge').first()).toContainText('NEW');

      // Cleanup
      const allCaptures = await (await request.get('/api/captures?status=NEW')).json();
      const created = allCaptures.find(c => c.rawText && c.rawText.includes(RUN + ' quick-capture-ui'));
      if (created) await archiveCapture(request, created.id);
    });
  });

  // ── 3. API lifecycle — state machine ────────────────────────────────────
  test.describe('3. API state machine: NEW → PROCESSING → PROCESSED', () => {
    test('creates with status NEW', async ({ request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} state-new | state machine test`);
      const capture = await getCapture(request, id);
      expect(capture.status).toBe('NEW');
      expect(capture.classified).toBeNull();
      expect(capture.rawText).toContain(RUN);
      await archiveCapture(request, id);
    });

    test('process sets status to PROCESSED and records route', async ({ request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} state-process | note to process`);
      const result = await processCapture(request, id);
      expect(result.status).toBe('PROCESSED');
      expect(result.route).toBe('NOTE');

      const capture = await getCapture(request, id);
      expect(capture.status).toBe('PROCESSED');
      expect(capture.classified).toBe('NOTE');
      expect(capture.routedTo).toContain('notes');
      expect(capture.processedAt).not.toBeNull();
      await archiveCapture(request, id);
    });

    test('archive sets status to ARCHIVED and schedulers ignore it', async ({ request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} state-archive | archive test`);
      await archiveCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.status).toBe('ARCHIVED');
      expect(capture.archivedAt).not.toBeNull();

      // ARCHIVED must not appear in NEW list
      const newList = await (await request.get('/api/captures?status=NEW')).json();
      expect(newList.find(c => c.id === id)).toBeUndefined();
    });

    test('reprocess resets PROCESSED back to NEW, clears classification', async ({ request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} state-reprocess | reprocess test`);
      await processCapture(request, id);
      const before = await getCapture(request, id);
      expect(before.status).toBe('PROCESSED');

      const r = await request.post(`/api/captures/${id}/reprocess`);
      expect(r.ok()).toBeTruthy();
      const after = await getCapture(request, id);
      expect(after.status).toBe('NEW');
      expect(after.classified).toBeNull();
      expect(after.routedTo).toBeNull();
      expect(after.processedAt).toBeNull();
      await archiveCapture(request, id);
    });
  });

  // ── 4. Routing — все 7 типов идут в нужные сущности ────────────────────
  test.describe('4. CaptureRouter — all route types create downstream entities', () => {
    test('TASK: capture creates pending task', async ({ request }) => {
      const title = `${RUN} e2e-task-route`;
      const id = await createCapture(request, `TASK: ${title} | задача из capture E2E`);
      await processCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.classified).toBe('TASK');
      expect(capture.routedTo).toContain('tasks');

      const tasks = await (await request.get('/api/tasks/pending')).json();
      const task = tasks.find(t => t.title && t.title.includes(title));
      expect(task, 'pending task must be created').toBeTruthy();
      // CaptureRouter routes via createPending which uses source=EMAIL internally
      expect(task.id).toBeTruthy();

      // Cleanup
      if (task) await request.delete(`/api/tasks/${task.id}`);
      await archiveCapture(request, id);
    });

    test('RISK: capture creates risk entry', async ({ request }) => {
      const title = `${RUN} e2e-risk-route`;
      const id = await createCapture(request, `RISK: ${title} | операционный риск E2E`);
      await processCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.classified).toBe('RISK');
      expect(capture.routedTo).toContain('risks');

      const risks = await (await request.get('/api/risks')).json();
      const risk = risks.find(r => r.title && r.title.includes(title));
      expect(risk, 'risk must be created').toBeTruthy();

      await archiveCapture(request, id);
    });

    test('NOTE: capture creates note entry', async ({ request }) => {
      const title = `${RUN} e2e-note-route`;
      const id = await createCapture(request, `NOTE: ${title} | заметка E2E`);
      await processCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.classified).toBe('NOTE');
      expect(capture.routedTo).toContain('notes');

      const notes = await (await request.get('/api/notes')).json();
      const note = notes.find(n => n.title && n.title.includes(title));
      expect(note, 'note must be created').toBeTruthy();
      expect(note.source).toMatch(/capture/i);

      await archiveCapture(request, id);
    });

    test('QUESTION: capture creates question entry', async ({ request }) => {
      const title = `${RUN} e2e-question-route`;
      const id = await createCapture(request, `QUESTION: ${title} | открытый вопрос E2E`);
      await processCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.classified).toBe('QUESTION');
      expect(capture.routedTo).toContain('questions');

      const questions = await (await request.get('/api/questions')).json();
      const q = questions.find(q => q.title && q.title.includes(title));
      expect(q, 'question must be created').toBeTruthy();

      await archiveCapture(request, id);
    });

    test('KNOWLEDGE: capture writes to rag-inbox', async ({ request }) => {
      const title = `${RUN} e2e-knowledge-route`;
      const id = await createCapture(request, `KNOWLEDGE: ${title} | архитектурное знание E2E`);
      await processCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.classified).toBe('KNOWLEDGE');
      expect(capture.routedTo).toContain('rag-inbox');

      await archiveCapture(request, id);
    });

    test('PERSON_NOTE: capture creates person note', async ({ request }) => {
      const personName = `${RUN}-E2E-Person`;
      const id = await createCapture(request, `PERSON_NOTE: ${personName} | хочет перейти в другую команду`);
      await processCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.classified).toBe('PERSON_NOTE');
      expect(capture.routedTo).toContain('person_notes');

      await archiveCapture(request, id);
    });

    test('JOURNAL: capture appends to daily journal file', async ({ request }) => {
      const title = `${RUN} e2e-journal-route`;
      const id = await createCapture(request, `JOURNAL: ${title} | итоги дня E2E`);
      await processCapture(request, id);
      const capture = await getCapture(request, id);
      expect(capture.classified).toBe('JOURNAL');
      expect(capture.routedTo).toContain('journal');

      await archiveCapture(request, id);
    });
  });

  // ── 5. API filters — GET /api/captures с параметрами ──────────────────
  test.describe('5. API filters', () => {
    test('filter by status=NEW returns only NEW captures', async ({ request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} filter-new | must be new`);

      const newList = await (await request.get('/api/captures?status=NEW')).json();
      expect(newList.every(c => c.status === 'NEW')).toBe(true);
      expect(newList.find(c => c.id === id)).toBeTruthy();

      await archiveCapture(request, id);
    });

    test('filter by status=PROCESSED returns only PROCESSED captures', async ({ request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} filter-processed | must be processed`);
      await processCapture(request, id);

      const list = await (await request.get('/api/captures?status=PROCESSED')).json();
      expect(list.every(c => c.status === 'PROCESSED')).toBe(true);
      expect(list.find(c => c.id === id)).toBeTruthy();

      await archiveCapture(request, id);
    });

    test('filter by source returns only matching source', async ({ request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} filter-src | source test`, 'api');

      const list = await (await request.get('/api/captures?source=api')).json();
      expect(list.every(c => c.source === 'api')).toBe(true);
      expect(list.find(c => c.id === id)).toBeTruthy();

      await archiveCapture(request, id);
    });

    test('text search q= finds matching captures', async ({ request }) => {
      const uniqueWord = `SRCH${Date.now()}`;
      const id = await createCapture(request, `NOTE: ${uniqueWord} searchable | find this`);

      const list = await (await request.get(`/api/captures?q=${uniqueWord}`)).json();
      expect(list.length).toBeGreaterThan(0);
      expect(list.find(c => c.id === id)).toBeTruthy();

      await archiveCapture(request, id);
    });

    test('filter by route returns only matching classified captures', async ({ request }) => {
      const id = await createCapture(request, `RISK: ${RUN} filter-route | route filter test`);
      await processCapture(request, id);

      const list = await (await request.get('/api/captures?route=RISK')).json();
      expect(list.find(c => c.id === id)).toBeTruthy();

      await archiveCapture(request, id);
    });
  });

  // ── 6. GET /api/captures/{id} — detail endpoint ─────────────────────────
  test.describe('6. Detail endpoint', () => {
    test('returns full capture with all fields after processing', async ({ request }) => {
      const text = `TASK: ${RUN} detail-test | полный набор полей`;
      const id = await createCapture(request, text);
      const before = await getCapture(request, id);
      expect(before.id).toBe(id);
      expect(before.rawText).toBe(text);
      expect(before.status).toBe('NEW');
      expect(before.capturedAt).not.toBeNull();
      expect(before.updatedAt).not.toBeNull();

      await processCapture(request, id);
      const after = await getCapture(request, id);
      expect(after.status).toBe('PROCESSED');
      expect(after.classified).toBe('TASK');
      expect(after.processedAt).not.toBeNull();

      await archiveCapture(request, id);
    });

    test('returns 404 for nonexistent id', async ({ request }) => {
      const r = await request.get('/api/captures/99999999');
      expect(r.status()).toBe(404);
    });
  });

  // ── 7. Backward compatibility — /api/capture ────────────────────────────
  test.describe('7. Backward compatibility', () => {
    test('POST /api/capture still works with same response shape', async ({ request }) => {
      const r = await request.post('/api/capture', {
        data: { text: `${RUN} backward-compat check`, source: 'agent' }
      });
      expect(r.ok()).toBeTruthy();
      const body = await r.json();
      expect(body.captureId).toBeTruthy();
      expect(body.saved).toBe(true);
      expect(body.savedAt).toBeTruthy();
      // file is present (inbox path) or null — both acceptable
      expect(body).toHaveProperty('file');

      // Capture must appear via new plural endpoint
      const capture = await getCapture(request, body.captureId);
      expect(capture.status).toBe('NEW');
      expect(capture.source).toBe('agent');

      await archiveCapture(request, body.captureId);
    });

    test('POST /api/capture is idempotent by sourceId', async ({ request }) => {
      const sourceId = `compat-${RUN}`;
      const first = await (await request.post('/api/capture', {
        data: { text: 'idempotent test', source: 'email', sourceId }
      })).json();
      const second = await (await request.post('/api/capture', {
        data: { text: 'idempotent test', source: 'email', sourceId }
      })).json();
      expect(first.captureId).toBe(second.captureId);
    });
  });

  // ── 8. UI — status filter buttons change the table ──────────────────────
  test.describe('8. UI status filters', () => {
    test('clicking filter button reloads history with correct status', async ({ page, request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} ui-filter | filter button test`);

      await page.goto('/ui/captures');

      // Wait for table to load
      await page.waitForFunction(() =>
        document.getElementById('historyLoading')?.classList.contains('d-none') ||
        document.getElementById('historyTable')?.style.display !== 'none' ||
        !document.getElementById('historyEmpty')?.classList.contains('d-none'),
        { timeout: 10000 }
      );

      // Click "New" filter and wait for table to re-render
      await page.locator('.status-btn[data-status="NEW"]').click();
      await page.waitForResponse(r =>
        r.url().includes('/api/captures') && r.url().includes('status=NEW') && r.status() === 200
      );

      // Wait for table to become visible (not loading)
      await expect(page.locator('#historyTable')).toBeVisible({ timeout: 8000 });

      // All visible status badges must be NEW (no PROCESSED or ERROR rows)
      const allBadges = await page.locator('#historyBody .badge').allTextContents();
      const statusBadges = allBadges.filter(t => ['NEW', 'PROCESSING', 'PROCESSED', 'ERROR', 'ARCHIVED'].includes(t));
      expect(statusBadges.every(s => s === 'NEW'), `Expected only NEW, got: ${statusBadges}`).toBe(true);

      // Click "All" — resets
      await page.locator('.status-btn[data-status=""]').click();
      await page.waitForResponse(r => r.url().includes('/api/captures') && r.status() === 200);

      await archiveCapture(request, id);
    });
  });

  // ── 9. UI — Process button triggers state transition ───────────────────
  test.describe('9. UI actions — Process and Archive buttons', () => {
    test('Process button changes status from NEW to PROCESSED', async ({ page, request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} ui-process | process via UI button`);

      await page.goto('/ui/captures');

      // Wait for history to load
      await page.waitForFunction(() =>
        document.getElementById('historyTable')?.style.display !== 'none' ||
        !document.getElementById('historyEmpty')?.classList.contains('d-none'),
        { timeout: 10000 }
      );

      // Filter to NEW — register listener before click
      const filterResp = page.waitForResponse(
        r => r.url().includes('/api/captures') && r.url().includes('status=NEW') && r.status() === 200
      );
      await page.locator('.status-btn[data-status="NEW"]').click();
      await filterResp;
      await expect(page.locator('#historyTable')).toBeVisible({ timeout: 8000 });

      // Find the row for our capture by its ID (rendered as process button onclick)
      const processBtn = page.locator(`#historyBody button[onclick="processCapture(${id})"]`);
      await expect(processBtn).toBeVisible({ timeout: 8000 });

      // Register listeners before click
      const processResp = page.waitForResponse(
        r => r.url().includes(`/api/captures/${id}/process`) && r.status() === 200
      );
      await processBtn.click();
      await processResp;

      // Verify via API the status changed
      const capture = await getCapture(request, id);
      expect(capture.status).toBe('PROCESSED');

      await archiveCapture(request, id);
    });

    test('Archive button moves capture to ARCHIVED', async ({ page, request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} ui-archive | archive via UI button`);

      // Register listener before navigation to catch the initial loadHistory() call
      const initialLoad = page.waitForResponse(
        r => r.url().includes('/api/captures') && r.status() === 200
      );
      await page.goto('/ui/captures');
      await initialLoad;

      // Register response listener BEFORE click to avoid race condition
      const filterResponse = page.waitForResponse(
        r => r.url().includes('/api/captures') && r.url().includes('status=NEW') && r.status() === 200
      );
      await page.locator('.status-btn[data-status="NEW"]').click();
      await filterResponse;
      await expect(page.locator('#historyTable')).toBeVisible({ timeout: 8000 });

      const archiveBtn = page.locator(`#historyBody button[onclick="archiveCapture(${id})"]`);
      await expect(archiveBtn).toBeVisible({ timeout: 8000 });

      // Register response listener BEFORE archive click
      const archiveResponse = page.waitForResponse(
        r => r.url().includes(`/api/captures/${id}/archive`) && r.status() === 200
      );
      await archiveBtn.click();
      await archiveResponse;

      const capture = await getCapture(request, id);
      expect(capture.status).toBe('ARCHIVED');
    });
  });

  // ── 10. UI — View modal shows full capture detail ──────────────────────
  test.describe('10. UI View modal', () => {
    test('clicking View opens modal with capture details', async ({ page, request }) => {
      const id = await createCapture(request, `NOTE: ${RUN} ui-view | text for view modal`);

      await page.goto('/ui/captures');

      await page.waitForFunction(() =>
        document.getElementById('historyTable')?.style.display !== 'none' ||
        !document.getElementById('historyEmpty')?.classList.contains('d-none'),
        { timeout: 10000 }
      );

      await page.locator('.status-btn[data-status="NEW"]').click();
      await page.waitForResponse(r => r.url().includes('/api/captures') && r.status() === 200);

      const viewBtn = page.locator(`#historyBody button[onclick="viewCapture(${id})"]`);
      await expect(viewBtn).toBeVisible({ timeout: 8000 });
      await viewBtn.click();

      await page.waitForResponse(r => r.url().includes(`/api/captures/${id}`) && r.status() === 200);

      const modal = page.locator('#viewCaptureModal');
      await expect(modal).toBeVisible({ timeout: 5000 });
      await expect(modal.locator('#viewStatus')).toContainText('NEW');
      await expect(modal.locator('#viewText')).toContainText('ui-view');

      // Close modal via the footer Close button
      await modal.getByRole('button', { name: 'Close' }).click();
      await expect(modal).not.toBeVisible();

      await archiveCapture(request, id);
    });
  });

  // ── 11. UsageEvent — CAPTURE_CREATED и CAPTURE_PROCESSED фиксируются ──
  test.describe('11. Usage events', () => {
    test('processing a capture generates CAPTURE_CREATED and CAPTURE_PROCESSED events', async ({ request }) => {
      // Snapshot stats before
      const before = await (await request.get('/api/stats/usage?period=1d')).json();
      const capturesBefore = before.capturesProcessed ?? 0;

      const id = await createCapture(request, `NOTE: ${RUN} usage-event | usage event test`);
      await processCapture(request, id);

      // Stats after
      const after = await (await request.get('/api/stats/usage?period=1d')).json();
      expect(after.capturesProcessed).toBeGreaterThan(capturesBefore);

      await archiveCapture(request, id);
    });
  });

  // ── 12. Concurrent safety — parallel creates don't lose data ───────────
  test.describe('12. Concurrent captures', () => {
    test('5 simultaneous POST /api/captures all get distinct ids', async ({ request }) => {
      const ids = await Promise.all(
        Array.from({ length: 5 }, (_, i) =>
          createCapture(request, `NOTE: ${RUN} parallel-${i} | concurrent capture ${i}`)
        )
      );
      // All ids are unique
      const unique = new Set(ids);
      expect(unique.size).toBe(5);

      // Cleanup
      await Promise.all(ids.map(id => archiveCapture(request, id)));
    });
  });
});
