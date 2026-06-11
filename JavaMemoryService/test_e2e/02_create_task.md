# Scenario: Create Task — Full Flow

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Создать подтверждённую задачу через POST /api/tasks.
Проверить что задача появляется в плане дня через GET /api/tasks?date=...
Проверить поля: title, priority, status, source.

## Preconditions
- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432

## Steps

### Step 1 — Создать задачу на сегодня
```bash
TODAY=$(date +%Y-%m-%d)
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"E2E: Провести ревью кода\",
    \"date\": \"$TODAY\",
    \"priority\": \"HIGH\",
    \"description\": \"Автоматический E2E тест — создание задачи\",
    \"source\": \"MANUAL\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
TASK_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | Task ID: $TASK_ID"
```
**Expected:** HTTP 201, тело содержит `"status":"TODO"`, `"priority":"HIGH"`, `"source":"MANUAL"`
**Extract:** `id` из тела → `$TASK_ID`

### Step 2 — Задача видна в плане дня
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$TODAY"
```
**Expected:** HTTP 200, массив содержит объект с `"title":"E2E: Провести ревью кода"`

### Step 3 — Задача видна с фильтром по статусу TODO
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$TODAY&status=TODO"
```
**Expected:** HTTP 200, массив содержит задачу с `"id":$TASK_ID`

### Step 4 — GET /api/plans возвращает план на сегодня с задачей
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/plans?date=$TODAY"
```
**Expected:** HTTP 200, тело содержит `"E2E: Провести ревью кода"`

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/delete" > /dev/null
echo "Cleanup: task $TASK_ID deleted"
```
