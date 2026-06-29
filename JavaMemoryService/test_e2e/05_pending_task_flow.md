# Scenario: Pending Task Flow — от mail-agent до подтверждения

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Симулирует поток от JavaMailAgent: создать PENDING задачу через POST /api/tasks/pending,
убедиться что она попадает в очередь ожидания, подтвердить через /confirm,
проверить что статус стал TODO и задача появилась в плане дня.
Отдельно проверить reject: PENDING → ARCHIVED.

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать PENDING задачу (симуляция mail-agent)
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{
    "title": "E2E: Обсудить архитектуру payments",
    "description": "Письмо от Иванова: нужно обсудить до пятницы",
    "emailId": "e2e-pending-001",
    "sender": "ivanov@company.ru",
    "priority": "HIGH"
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
TASK_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | Task ID: $TASK_ID | Status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 201, `"status":"PENDING"`, `"emailId":"e2e-pending-001"`, `"priority":"HIGH"`
**Extract:** `id` → `$TASK_ID`

### Step 2 — Задача видна в очереди PENDING
```bash
curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.emailId == "e2e-pending-001")] | length'
```
**Expected:** результат `1`

### Step 3 — PENDING задача НЕ попадает в план дня (не в /api/tasks?date=...)
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$TODAY" \
  | jq '[.[] | select(.emailId == "e2e-pending-001")] | length'
```
**Expected:** результат `0` — PENDING задачи не в плане дня

### Step 4 — PENDING задача НЕ попадает в /api/context
```bash
curl -s http://localhost:8082/api/context \
  | grep -c "e2e-pending-001" || true
```
**Expected:** результат `0`

### Step 5 — Подтвердить задачу: PENDING → TODO
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "http://localhost:8082/api/tasks/$TASK_ID/confirm")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE | Status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 200, `"status":"TODO"`

### Step 6 — Задача больше не в PENDING очереди
```bash
curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.emailId == "e2e-pending-001")] | length'
```
**Expected:** результат `0`

### Step 7 — Задача появилась в плане дня со статусом TODO
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$TODAY&status=TODO" \
  | jq '.[] | select(.emailId == "e2e-pending-001") | {id, title, status, priority}'
```
**Expected:** объект с `"status":"TODO"`, `"priority":"HIGH"`, `"title":"E2E: Обсудить архитектуру payments"`

### Step 8 — Создать вторую PENDING задачу для проверки reject
```bash
RESPONSE=$(curl -s -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{
    "title": "E2E: Задача для отклонения",
    "emailId": "e2e-pending-002",
    "sender": "spam@company.ru",
    "priority": "LOW"
  }')
TASK_ID_2=$(echo "$RESPONSE" | jq -r '.id')
echo "Task 2 ID: $TASK_ID_2"
```
**Expected:** HTTP 201, `"status":"PENDING"`
**Extract:** `id` → `$TASK_ID_2`

### Step 9 — Отклонить вторую задачу: PENDING → ARCHIVED
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "http://localhost:8082/api/tasks/$TASK_ID_2/reject")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE | Status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 200, `"status":"ARCHIVED"`

### Step 10 — Отклонённая задача не видна нигде
```bash
curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.emailId == "e2e-pending-002")] | length'
```
**Expected:** результат `0`

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/delete" > /dev/null
echo "Cleanup done"
```
