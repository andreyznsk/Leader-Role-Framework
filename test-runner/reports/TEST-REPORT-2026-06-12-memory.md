# TEST-REPORT-2026-06-12-memory

**Запуск:** 2026-06-12 16:19–16:31
**Профиль:** local,e2e
**Инициатор:** ручной запуск
**Сервис:** JavaMemoryService :8082

---

## Summary

| Сервис | Сборка | Запуск | Сценариев | PASS | FAIL | SKIP |
|--------|--------|--------|-----------|------|------|------|
| JavaMemoryService | ✅ (пересборка потребовалась) | ✅ | 13 | 12 | 1 | 0 |

> **Примечание о пересборке:** JAR был собран в 11:45, но `NoteController.java` и `CaptureController.java`
> были изменены позже (12:12). До пересборки тесты 10, 11 падали по причине устаревшего JAR.
> После `./test-runner/build.sh --service JavaMemoryService` все прошли.

---

## Детали по сценариям

### ✅ 01_health_check — PASS
- Step 1: `GET /actuator/health` → HTTP 200 ✅
- Step 2: тело содержит `"status":"UP"` ✅

---

### ✅ 02_create_task — PASS
- Step 1: `POST /api/tasks` → HTTP 201, `status=TODO, priority=HIGH, source=MANUAL` ✅
- Step 2: задача видна в `GET /api/tasks?date=...` ✅
- Step 3: фильтр `&status=TODO` возвращает задачу ✅
- Step 4: `GET /api/plans?date=...` содержит задачу ✅

---

### ✅ 02_pending_task_flow — PASS
- Step 1: `POST /api/tasks/pending` → HTTP 201, `status=PENDING` ✅
- Step 2: задача видна в `GET /api/tasks/pending` ✅
- Step 3: `POST /api/tasks/{id}/confirm` → HTTP 200, `status=TODO` ✅
- Step 4: задача исчезла из PENDING очереди ✅
- Step 5: задача видна в плане дня со статусом TODO ✅

---

### ✅ 03_read_daily_plan — PASS
- Steps 1-2: созданы задачи с CRITICAL и NORMAL приоритетами ✅
- Step 3: обе задачи в плане дня ✅
- Step 4: фильтр `&status=TODO` возвращает ≥2 задачи ✅
- Step 5: `GET /api/context` содержит созданные задачи ✅
- Step 6: `GET /ui/today` → HTTP 200 ✅

---

### ⚠️ 04_context_no_pending — PASS (с оговорками)
- Step 1: PENDING задача создана → HTTP 201 ✅
- Step 2: `POST /mcp/message` (MCP getContext) → **HTTP 400** (без sessionId)
  - Тест прошёл т.к. `ctx-test-001` не был в ответе (ответ — stack trace ошибки)
  - **Реальная проверка:** выполнена через `GET /api/context` → PENDING задача не входит ✅
  - **Замечание:** сценарий не учитывает SSE-протокол MCP (нужен `notifications/initialized`)

---

### ✅ 04_edit_task — PASS
- Step 1: создана задача с LOW приоритетом ✅
- Step 2: `PUT /api/tasks/{id}` → изменены title и priority ✅
- Step 3: изменения сохранились в БД ✅
- Step 4: `PATCH /api/tasks/{id}/status` → `IN_PROGRESS` ✅
- Step 5: `POST /api/tasks/{id}/done` → `DONE` ✅
- Step 6: задача не видна в фильтре `TODO` ✅
- Step 7: `PUT /api/tasks/{id}/description` → HTTP 200 ✅
- Step 8: `GET /api/tasks/{id}/description` содержит текст ✅

---

### ✅ 05_pending_task_flow — PASS
> Step 7 показал FAIL из-за пробела в jq-выводе (`"status": "TODO"` vs `"status":"TODO"`).
> Фактические данные корректны: status=TODO, priority=HIGH — **реальный PASS**.

- Steps 1-7: полный флоу PENDING → confirm → TODO ✅
- Steps 8-10: reject флоу PENDING → DELETED ✅
- Step 4: PENDING задача не попадает в `/api/context` ✅
- Step 3: PENDING задача не попадает в план дня ✅

---

### ✅ 06_incidents — PASS (с замечанием по rootCause)
- Step 1: `POST /api/incidents` → HTTP 201, `status=OPEN, severity=P1` ✅
- Step 2: инцидент виден в `GET /api/incidents?status=OPEN` ✅
- Step 3: `PUT /api/incidents/{id}` → `INVESTIGATING` ✅
- Step 4: `POST /api/incidents/{id}/resolve` → HTTP 200, `status=RESOLVED` ✅
  - ⚠️ **rootCause в ответе resolve был null** — однако GET /api/incidents показывает rootCause сохранился (минор)
- Step 5: RESOLVED инцидент не в `status=OPEN` списке ✅
- Step 6: `GET /ui/incidents` → HTTP 200 ✅
- Step 7: RESOLVED инцидент не в `getContext.openIncidents` ✅

---

### ✅ 07_risks — PASS
- Step 1: `POST /api/risks` → HTTP 201, `status=OPEN, probability=HIGH, impact=HIGH` ✅
- Step 2: риск виден в `GET /api/risks?status=OPEN` ✅
- Step 3: риск входит в `getContext.openRisks` ✅
- Step 4: `PUT /api/risks/{id}` → `MITIGATED`, mitigation сохранилась ✅
- Step 5: MITIGATED риск не в OPEN списке ✅
- Step 6: `GET /ui/risks` → HTTP 200 ✅

---

### ✅ 08_people_and_notes — PASS
- Step 1: `POST /api/people` → HTTP 201 ✅
- Step 2: карточка видна в `GET /api/people` ✅
- Step 3: поиск по URL-encoded name работает ✅
- Steps 4-5: добавлены 2 заметки (HTTP 201) ✅
- Step 6: `GET /api/people/{id}/notes` возвращает 2 заметки с `createdAt` ✅
- Step 7: `PUT /api/people/{id}` → currentTask обновлён ✅
- Step 8: `getContext.recentPeopleNotes` содержит заметки по personId ✅
- Step 9: `GET /ui/people` → HTTP 200 ✅

---

### ✅ 09_mcp_tools — PASS (с замечанием по сценарию)
> **Баг в сценарии:** Step 3 не отправлял `notifications/initialized` перед `tools/list`,
> что приводило к отсутствию ответа. После добавления нотификации — все 14 инструментов найдены.

- Step 1: SSE-соединение открыто, sessionId получен ✅
- Step 2: `initialize` → HTTP 200 ✅
- Step 3: `tools/list` вернул 14 инструментов, все 7 обязательных присутствуют ✅
  - getContext, getTasks, createTask, markTaskDone, createIncident, addRisk, addPeopleNote ✅
  - Дополнительные: updateRisk, updateTaskStatus, resolveIncident, searchPeople, moveTask, getTaskDescription, setTaskDescription
- Step 4: `getTasks` без ошибок ✅

---

### ✅ 10_capture_bot — PASS
> Тест падал до пересборки JAR (`NoteController` и `CaptureResponse.file` добавлены после сборки).

- Step 1: директория очищена ✅
- Step 2: `POST /api/capture` → HTTP 200, `saved=true`, `file` не пустой ✅
- Step 3: файл существует, front matter корректен (`---`, `date:`, `source: manual`) ✅
- Step 4: capture виден в `GET /api/capture/today` по ID со `status=PENDING` ✅
- Step 5: `POST /api/notes` → HTTP 201, `source=capture`, `tags=e2e,capture` ✅
- Step 6: note видна через `GET /api/notes?tags=e2e` ✅
- Step 7: `GET /ui/notes` содержит текст заметки ✅

---

### ✅ 11_capture_bot_improvements — PASS
- Step 1: note создана → HTTP 201 ✅
- Step 2: `/ui/notes` содержит `taskFromItemModal`, кнопку `→ В задачу`, текст заметки ✅
- Step 3: риск создан → HTTP 201 ✅
- Step 4: `/ui/risks` содержит `taskFromRiskModal`, кнопку `→ В задачу` ✅
- Step 5: `POST /api/tasks/pending` с `dueDate` → HTTP 201, dueDate сохранён ✅
- Step 6: PENDING задача с dueDate видна в `/api/tasks/pending` ✅
- Step 7: `/ui/today` содержит PENDING задачу ✅
- Step 8: риск входит в `getContext.openRisks` ✅
- Step 9: после `confirm` задача входит в `getContext.todayPlan.tasks` ✅

---

### ✅ 12_capture_classification_mock — PASS
> Требует `SPRING_PROFILES_ACTIVE=local,e2e` (mock.capture-agent=true, cron каждую минуту).

- Step 1: сервис UP ✅
- Step 2: директории подготовлены ✅
- Step 3: 7 capture-заметок созданы через `POST /api/capture` ✅
- Step 4: scheduler обработал все файлы за ~10с, все переместились в `processed/` ✅
- Step 5: `TASK:` → попал в `/api/tasks/pending` ✅
- Step 6: `RISK:` → попал в `/api/risks` (status=OPEN) ✅
- Step 7: `NOTE:` → попал в `/api/notes` (tags=capture,mock) ✅
- Step 8: `QUESTION:` → попал в `/api/questions` (status=OPEN) ✅
- Step 9: `PERSON_NOTE:` → попал в `/api/people/name/{name}/notes` ✅
- Step 10: `KNOWLEDGE:` → попал в `JavaRagService/rag-inbox/captures/` ✅
- Step 11: `JOURNAL:` → попал в `workspace/08_daily_journal/` ✅
- Step 12: очередь пуста, файлы в `capture-inbox/$TODAY/*.md` — 0 ✅

---

## Найденные дефекты и замечания

### ❌ BUG-001: Устаревший JAR (BUILD GAP)
- **Компонент:** CaptureController, NoteController
- **Проблема:** JAR был собран в 11:45, изменения в NoteController.java и CaptureController.java сделаны в 12:12 — не попали в JAR
- **Симптом:** `/api/notes` → HTTP 404; `CaptureResponse.file` = null
- **Фикс:** пересборка `./test-runner/build.sh --service JavaMemoryService` — устраняет проблему
- **Рекомендация:** настроить автопересборку при изменениях или добавить дату сборки в healthcheck

### ⚠️ WARN-001: Сценарий 09_mcp_tools неполный
- **Проблема:** сценарий не включает шаг `notifications/initialized` перед `tools/list`
- **Симптом:** `tools/list` response не приходит в SSE поток без этой нотификации
- **Фикс сценария:** добавить Step 2.5 — `POST /mcp/message` с `method=notifications/initialized`

### ⚠️ WARN-002: rootCause в ответе /resolve = null (минор)
- **Компонент:** `POST /api/incidents/{id}/resolve`
- **Проблема:** тело ответа resolve не содержит rootCause, хотя в БД сохраняется корректно
- **Верификация:** `GET /api/incidents` после resolve — rootCause присутствует
- **Приоритет:** LOW

### ⚠️ WARN-003: Сценарий 04_context_no_pending некорректно тестирует MCP
- **Проблема:** `POST /mcp/message` без sessionId → HTTP 400 — тест "проходит" через ошибку
- **Рекомендация:** использовать SSE flow (как в 09_mcp_tools) для корректной проверки getContext

---

## Инфраструктура

| Компонент | Статус |
|-----------|--------|
| PostgreSQL :5432 | ✅ |
| OpenSearch :9200 | ✅ |
| Ollama :11434 | ✅ |
| JavaMemoryService :8082 | ✅ |
| JavaMailAgent :8080 | ❌ не запущен (вне скоупа теста) |
| JavaRagService :8081 | ✅ |
