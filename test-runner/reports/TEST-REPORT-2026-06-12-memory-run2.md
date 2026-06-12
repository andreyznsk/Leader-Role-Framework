# TEST-REPORT-2026-06-12-memory-run2

**Запуск:** 2026-06-12 16:36–16:44
**Профиль:** local,e2e
**Инициатор:** ручной запуск (после пересборки и перезапуска)
**Сервис:** JavaMemoryService :8082 (PID 113702)

---

## Summary

| Сервис | Сборка | Запуск | Сценариев | PASS | FAIL | SKIP |
|--------|--------|--------|-----------|------|------|------|
| JavaMemoryService | ✅ | ✅ | 13 | **13** | **0** | 0 |

**Все сценарии прошли. Сервис полностью работоспособен.**

---

## Детали по сценариям

### ✅ 01_health_check — PASS
- Step 1: `GET /actuator/health` → HTTP 200 ✅
- Step 2: `"status":"UP"` ✅

### ✅ 02_create_task — PASS
- Step 1: `POST /api/tasks` → HTTP 201, `status=TODO, priority=HIGH, source=MANUAL` ✅
- Step 2: задача в `GET /api/tasks?date=TODAY` ✅
- Step 3: фильтр `&status=TODO` работает ✅
- Step 4: `GET /api/plans?date=TODAY` содержит задачу ✅

### ✅ 02_pending_task_flow — PASS
- Step 1: `POST /api/tasks/pending` → HTTP 201, `status=PENDING` ✅
- Step 2: задача в `GET /api/tasks/pending` ✅
- Step 3: `POST /confirm` → `status=TODO` ✅
- Step 4: задача исчезла из PENDING ✅
- Step 5: задача в плане дня со статусом TODO ✅

### ✅ 03_read_daily_plan — PASS
- Steps 1-2: CRITICAL и NORMAL задачи созданы ✅
- Step 3: обе в плане дня ✅
- Step 4: фильтр `status=TODO` → ≥2 результата ✅
- Step 5: `/api/context` содержит задачи ✅
- Step 6: `GET /ui/today` → HTTP 200 ✅

### ✅ 04_context_no_pending — PASS
- Step 1: PENDING задача создана → HTTP 201 ✅
- Step 2: `/api/context` не содержит PENDING задачу ✅
  - Прямой MCP вызов без sessionId → HTTP 400 (ожидаемо, SSE-протокол требует sessionId)

### ✅ 04_edit_task — PASS
- Step 1: задача создана (LOW) ✅
- Step 2: `PUT /api/tasks/{id}` → title и priority обновлены ✅
- Step 3: изменения сохранились в БД ✅
- Step 4: `PATCH /status` → `IN_PROGRESS` ✅
- Step 5: `POST /done` → `DONE` ✅
- Step 6: задача не в фильтре `TODO` ✅
- Step 7: `PUT /description` → HTTP 200 ✅
- Step 8: `GET /description` содержит текст ✅

### ✅ 05_pending_task_flow — PASS
- Steps 1-7: полный флоу PENDING → confirm → TODO ✅
  - Step 7: `status=TODO, priority=HIGH` — корректно ✅
- Steps 8-10: reject флоу PENDING → DELETED ✅
- PENDING задача не попадает в `/api/context` и план дня ✅

### ✅ 06_incidents — PASS
- Step 1: `POST /api/incidents` → HTTP 201, `status=OPEN, severity=P1` ✅
- Step 2: инцидент виден в `?status=OPEN` ✅
- Step 3: `PUT` → `INVESTIGATING` ✅
- Step 4: `POST /resolve` → HTTP 200, `status=RESOLVED`, `rootCause` сохранён ✅
  - Примечание: `\n` в JSON-строке `actionItems` вызывает jq parse error при отображении, но HTTP 200 и resolve работает корректно
- Step 5: RESOLVED не в OPEN ✅
- Step 6: `/ui/incidents` → HTTP 200 ✅
- Step 7: RESOLVED не в `getContext.openIncidents` ✅

### ✅ 07_risks — PASS
- Step 1: `POST /api/risks` → HTTP 201, `status=OPEN, probability=HIGH, impact=HIGH` ✅
- Step 2: риск в `?status=OPEN` ✅
- Step 3: риск в `getContext.openRisks` ✅
- Step 4: `PUT` → `MITIGATED`, mitigation = "Knowledge transfer проведён." ✅
- Step 5: MITIGATED не в OPEN ✅
- Step 6: `/ui/risks` → HTTP 200 ✅

### ✅ 08_people_and_notes — PASS
- Step 1: `POST /api/people` → HTTP 201 ✅
- Step 2: карточка в общем списке ✅
- Step 3: поиск по URL-encoded name работает ✅
- Steps 4-5: 2 заметки добавлены (HTTP 201) ✅
- Step 6: `GET /people/{id}/notes` → 2 заметки с `createdAt` ✅
- Step 7: `PUT /people/{id}` → currentTask обновлён ✅
- Step 8: `getContext.recentPeopleNotes` содержит заметки ✅
- Step 9: `/ui/people` → HTTP 200 ✅

### ✅ 09_mcp_tools — PASS
- Step 1: SSE-соединение, sessionId получен ✅
- Step 2: `initialize` → HTTP 200 ✅
- Step 3: `tools/list` — все 7 обязательных инструментов ✅
  - ✅ getContext, getTasks, createTask, markTaskDone, createIncident, addRisk, addPeopleNote
  - Итого 14 инструментов в сервисе
- Step 4: `getTasks` без ошибок ✅

### ✅ 10_capture_bot — PASS
- Step 1: директория подготовлена ✅
- Step 2: `POST /api/capture` → HTTP 200, `saved=true`, `file` заполнен ✅
- Step 3: файл существует, front matter корректен ✅
- Step 4: capture в `/api/capture/today` со `status=PENDING` ✅
- Step 5: `POST /api/notes` → HTTP 201 ✅
- Step 6: note найдена через `?tags=e2e` ✅
- Step 7: `/ui/notes` содержит текст заметки ✅

### ✅ 11_capture_bot_improvements — PASS
- Step 1: note создана → HTTP 201 ✅
- Step 2: `/ui/notes` содержит `taskFromItemModal`, кнопку `→ В задачу` ✅
- Step 3: риск создан → HTTP 201 ✅
- Step 4: `/ui/risks` содержит `taskFromRiskModal`, кнопку `→ В задачу` ✅
- Step 5: PENDING задача с dueDate через `/api/tasks/pending` → HTTP 201 ✅
- Step 6: задача с dueDate в PENDING очереди ✅
- Step 7: `/ui/today` отображает PENDING задачу ✅
- Step 8: риск в `getContext.openRisks` ✅
- Step 9: после `confirm` задача в `getContext.todayPlan.tasks` ✅

### ✅ 12_capture_classification_mock — PASS
> Профиль `local,e2e`: `mock.capture-agent=true`, scheduler cron каждую минуту.

- Step 1: сервис UP ✅
- Step 2: директории подготовлены ✅
- Step 3: 7 capture-файлов создано через `POST /api/capture` ✅
- Step 4: scheduler обработал все файлы за **6 секунд** ✅
- Step 5: `TASK:` → `/api/tasks/pending` ✅
- Step 6: `RISK:` → `/api/risks` (OPEN) ✅
- Step 7: `NOTE:` → `/api/notes` (tags=capture,mock) ✅
- Step 8: `QUESTION:` → `/api/questions` (OPEN) ✅
- Step 9: `PERSON_NOTE:` → `/api/people/name/{name}/notes` ✅
- Step 10: `KNOWLEDGE:` → `JavaRagService/rag-inbox/captures/` ✅
- Step 11: `JOURNAL:` → `workspace/08_daily_journal/` ✅
- Step 12: очередь пуста ✅

---

## Замечания (не блокирующие)

### ⚠️ WARN-001: `\n` в JSON строках ломает jq-отображение
- **Компонент:** тест 06_incidents, actionItems
- **Причина:** bash `'...\n...'` передаёт literal `\n`, сервер сохраняет как реальный символ новой строки, jq не может распарсить неэкранированный `\n` в JSON строке
- **Влияние:** только отображение при тестировании, HTTP 200 и бизнес-логика работают корректно
- **Рекомендация:** в тест-сценарии заменить `\n` на `\\n` или убрать из однострочной JSON-строки

### ⚠️ WARN-002: Сценарий 04_context_no_pending не проверяет MCP напрямую
- **Причина:** POST /mcp/message без sessionId → HTTP 400
- **Реальная проверка:** выполняется через REST `/api/context` — PENDING задача не входит ✅
- **Рекомендация:** использовать SSE flow (как в 09_mcp_tools) для полной проверки

---

## Инфраструктура

| Компонент | Статус |
|-----------|--------|
| PostgreSQL :5432 | ✅ |
| OpenSearch :9200 | ✅ |
| Ollama :11434 | ✅ |
| JavaMemoryService :8082 | ✅ |
| JavaMailAgent :8080 | ❌ не запущен (вне скоупа) |
| JavaRagService :8081 | ✅ |
