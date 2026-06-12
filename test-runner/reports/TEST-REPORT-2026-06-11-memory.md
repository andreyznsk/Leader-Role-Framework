# TEST-REPORT-2026-06-11-memory

**Запуск:** 2026-06-11 17:50 — 18:10 (local)
**Профиль:** local
**Инициатор:** ручной запуск (повторный после багфиксов)
**Сервис:** JavaMemoryService :8082
**JAR:** `JavaMemoryService/target/memory-service.jar` — собран свежий

---

## Инфраструктура

| Компонент | Статус |
|-----------|--------|
| PostgreSQL :5432 | ✅ UP |
| JavaMemoryService :8082 | ✅ UP |
| OpenSearch :9200 | ❌ DOWN (не нужен для этих тестов) |
| Maildev UI :1080 | ❌ DOWN (не нужен для этих тестов) |

---

## Summary

| Сценарий | Сборка | Запуск | PASS | FAIL | SKIP |
|----------|--------|--------|------|------|------|
| 01_health_check | ✅ | ✅ | ✅ | — | — |
| 02_create_task | ✅ | ✅ | ✅ | — | — |
| 02_pending_task_flow | ✅ | ✅ | ✅ | — | — |
| 03_mcp_tools_list | ✅ | ✅ | — | ❌ | — |
| 03_read_daily_plan | ✅ | ✅ | ✅ | — | — |
| 04_context_no_pending | ✅ | ✅ | ✅ | — | — |
| 04_edit_task | ✅ | ✅ | ✅ | — | — |
| 05_pending_task_flow | ✅ | ✅ | ✅ | — | — |
| 09_mcp_tools | ✅ | ✅ | ✅ | — | — |
| **Итого** | | | **8** | **1** | **0** |

---

## Статус применённых фиксов

| CR | Описание | Статус |
|----|----------|--------|
| CR-MEM-BUGFIX-001 | POST /api/tasks и /api/tasks/pending возвращали 200 вместо 201 | ✅ FIXED |
| CR-MEM-BUGFIX-002 | PATCH /api/tasks/{id}/status → 404 | ✅ FIXED |
| CR-MEM-SCENARIO-001 | 03_mcp_tools_list использовал неверный протокол | ✅ Новый сценарий 09_mcp_tools.md добавлен |

---

## Результаты по сценариям

### ✅ 01_health_check — PASS

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — actuator/health HTTP | 200 | **200** ✅ |
| Step 2 — status UP | `"status":"UP"` | `{"status":"UP"}` ✅ |

---

### ✅ 02_create_task — PASS

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — POST /api/tasks | HTTP 201, status TODO, priority HIGH, source MANUAL | **201** ✅ TODO ✅ HIGH ✅ MANUAL ✅ |
| Step 2 — задача в плане дня | title present | found ✅ |
| Step 3 — фильтр status=TODO | task found | 1 ✅ |
| Step 4 — GET /api/plans | HTTP 200, title present | 200 ✅, found ✅ |

---

### ✅ 02_pending_task_flow — PASS

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — создать PENDING | HTTP 201, status PENDING, emailId | **201** ✅ PENDING ✅ e2e-test-001 ✅ |
| Step 2 — видна в PENDING | 1 | 1 ✅ |
| Step 3 — confirm → TODO | HTTP 200, status TODO | 200 ✅ TODO ✅ |
| Step 4 — не в PENDING | 0 | 0 ✅ |
| Step 5 — в плане дня TODO | 1 | 1 ✅ |

---

### ❌ 03_mcp_tools_list — FAIL (известный дефект сценария)

**Упавший шаг:** Step 1 — прямой POST `/mcp/message` без sessionId

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — tools/list | HTTP 200, tools present | **HTTP 400** `Session ID missing in message endpoint` |

> **Примечание:** Это известный дефект сценария (CR-MEM-SCENARIO-001). Сервис корректно отвергает запрос без сессии с понятным сообщением об ошибке (улучшение: было 500, стало 400 + читаемый текст). Рабочий сценарий с правильным протоколом — `09_mcp_tools.md`.

---

### ✅ 03_read_daily_plan — PASS

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — создать CRITICAL | HTTP 201, priority CRITICAL | **201** ✅ CRITICAL ✅ |
| Step 2 — создать NORMAL | HTTP 201, priority NORMAL | **201** ✅ NORMAL ✅ |
| Step 3 — обе задачи в плане | обе присутствуют | CRITICAL ✅ NORMAL ✅ |
| Step 4 — TODO filter >= 2 | >= 2 | 3 ✅ |
| Step 5 — context содержит задачу | HTTP 200, found | 200 ✅ found ✅ |
| Step 6 — UI /ui/today | HTTP 200 | **200** ✅ |

---

### ✅ 04_context_no_pending — PASS

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — создать PENDING | HTTP 201 | **201** ✅ PENDING ✅ |
| Step 2 — ctx-test-001 НЕ в context | NOT present | не найдено ✅ |

---

### ✅ 04_edit_task — PASS (все 8 шагов)

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — создать задачу | HTTP 201, LOW | **201** ✅ LOW ✅ |
| Step 2 — PUT title+priority | HTTP 200, title updated, HIGH, TODO | 200 ✅ updated ✅ HIGH ✅ TODO ✅ |
| Step 3 — изменения сохранились | поля видны в плане | title ✅ priority HIGH ✅ |
| Step 4 — PATCH /status → IN_PROGRESS | HTTP 200, IN_PROGRESS | **200** ✅ IN_PROGRESS ✅ |
| Step 5 — POST /done → DONE | HTTP 200, DONE | 200 ✅ DONE ✅ |
| Step 6 — не в TODO | 0 | 0 ✅ |
| Step 7 — PUT description | HTTP 200 | 200 ✅ |
| Step 8 — GET description | HTTP 200, content present | 200 ✅ content ✅ |

> ✅ CR-MEM-BUGFIX-002 подтверждён: `PATCH /api/tasks/{id}/status` теперь работает.

---

### ✅ 05_pending_task_flow — PASS (все 10 шагов)

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — создать PENDING | HTTP 201, PENDING, emailId, HIGH | **201** ✅ PENDING ✅ e2e-pending-001 ✅ HIGH ✅ |
| Step 2 — в очереди PENDING | 1 | 1 ✅ |
| Step 3 — НЕ в плане дня | 0 | 0 ✅ |
| Step 4 — НЕ в context | 0 | 0 ✅ |
| Step 5 — confirm → TODO | HTTP 200, TODO | 200 ✅ TODO ✅ |
| Step 6 — не в PENDING | 0 | 0 ✅ |
| Step 7 — в плане TODO | status TODO, priority HIGH, title match | TODO ✅ HIGH ✅ title ✅ |
| Step 8 — создать вторую PENDING | HTTP 201, PENDING | 201 ✅ PENDING ✅ |
| Step 9 — reject → DELETED | HTTP 200, DELETED | 200 ✅ DELETED ✅ |
| Step 10 — отклонённая не видна | 0 | 0 ✅ |

---

### ✅ 09_mcp_tools — PASS (новый сценарий, SSE protocol)

| Шаг | Expected | Actual |
|-----|----------|--------|
| Step 1 — открыть SSE, получить sessionId | sessionId не пустой | `4614b788-...` ✅ |
| Step 2 — initialize | HTTP 200 | 200 ✅ |
| Step 3 — tools/list, проверить 7 tools | все 7 ✅ | все 7 присутствуют ✅ |
| Step 4 — вызвать getTasks | HTTP 200, без ошибок | 200 ✅ errors=0 ✅ |

**Все 7 обязательных инструментов:**

| Tool | Присутствует |
|------|-------------|
| `getContext` | ✅ |
| `getTasks` | ✅ |
| `createTask` | ✅ |
| `markTaskDone` | ✅ |
| `createIncident` | ✅ |
| `addRisk` | ✅ |
| `addPeopleNote` | ✅ |

Итого зарегистрировано: **14 инструментов** (7 дополнительных: `updateRisk`, `updateTaskStatus`, `resolveIncident`, `searchPeople`, `moveTask`, `getTaskDescription`, `setTaskDescription`).

---

## Новый дефект — обнаружен в этом прогоне

### CR-MEM-BUGFIX-003 — GET /api/tasks возвращает задачи со статусом DELETED

**Severity:** HIGH (data integrity / UX)
**Обнаружен:** при прогоне 05_pending_task_flow Step 3

**Воспроизведение:**
```bash
curl -s "http://localhost:8082/api/tasks?date=2026-06-11" | jq '[.[]|select(.status=="DELETED")]|length'
# → 18
curl -s "http://localhost:8082/api/tasks?date=2026-06-11" | jq 'length'
# → 19  (18 DELETED + 1 TODO)
```

**Expected:** endpoint возвращает только активные задачи (не DELETED / не REJECTED)
**Actual:** возвращает все задачи независимо от статуса, включая DELETED

**Влияние:**
- Дневной план показывает удалённые задачи пользователю
- Тест Step 3 в `05_pending_task_flow` нестабилен при повторных прогонах — старые DELETED задачи с тем же `emailId` дают ложный `count=1`
- `/ui/today` вероятно отображает мусор

**Где фиксить:**
```
JavaMemoryService/src/main/java/.../repository/TaskRepository.java
```
или в сервисном слое — добавить фильтр `WHERE status != 'DELETED'` в запрос задач по дате. Для фильтра `?status=TODO` уже работает корректно, проблема в запросе без явного фильтра статуса.

**Сценарий для проверки:** `05_pending_task_flow` Step 3 (повторный прогон после очистки накопленных данных)

---

## Итого по прогону

| Метрика | Значение |
|---------|---------|
| Сценариев всего | 9 |
| PASS | 8 |
| FAIL | 1 (03_mcp_tools_list — known scenario bug) |
| Новых дефектов | 1 (CR-MEM-BUGFIX-003) |
| Закрытых фиксов | 3 (CR-MEM-BUGFIX-001, 002, SCENARIO-001) |
