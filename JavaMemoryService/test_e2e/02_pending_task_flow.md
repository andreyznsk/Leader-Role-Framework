# Scenario: Pending Task Flow

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432

## Steps

### Step 1 — Создать PENDING задачу
```bash
curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{"title":"E2E Test Task","description":"Automated E2E test","emailId":"e2e-test-001","sender":"e2e@test.com","priority":"HIGH"}'
```
**Expected:** HTTP 201, тело содержит `"status":"PENDING"` и `"emailId":"e2e-test-001"`
**Extract:** `id` из тела ответа → сохранить как `$TASK_ID`

### Step 2 — GET /api/tasks/pending — задача видна
```bash
curl -s http://localhost:8082/api/tasks/pending
```
**Expected:** HTTP 200, массив содержит объект с `"emailId":"e2e-test-001"`

### Step 3 — Подтвердить задачу (PENDING → TODO)
```bash
curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/tasks/$TASK_ID/confirm
```
**Expected:** HTTP 200, тело содержит `"status":"TODO"`

### Step 4 — Задача больше не в PENDING
```bash
curl -s http://localhost:8082/api/tasks/pending
```
**Expected:** HTTP 200, массив НЕ содержит `"emailId":"e2e-test-001"`

### Step 5 — Задача видна в задачах дня
```bash
DATE=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$DATE&status=TODO"
```
**Expected:** HTTP 200, массив содержит `"emailId":"e2e-test-001"`

## Cleanup
```bash
# Удалить тестовую задачу
curl -s -X POST http://localhost:8082/api/tasks/$TASK_ID/reject
```
