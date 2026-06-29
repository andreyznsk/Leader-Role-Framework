# Scenario: Task Timeline and Archive Flow

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Проверяет audit timeline для задачи: создание, смену статуса, обновление описания,
ручной комментарий и архивирование через API.

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать задачу
```bash
TODAY=$(date +%Y-%m-%d)
BODY=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"E2E Timeline Task\",
    \"date\": \"$TODAY\",
    \"priority\": \"HIGH\",
    \"source\": \"MANUAL\"
  }")
TASK_ID=$(echo "$BODY" | jq -r '.id')
echo "$BODY" | jq '{id, title, status, priority}'
```
**Expected:** `"status":"TODO"`

### Step 2 — Проверить initial timeline
```bash
curl -s "http://localhost:8082/api/tasks/$TASK_ID/timeline" | jq '.[0]'
```
**Expected:** первый event имеет `"eventType":"TASK_CREATED"`

### Step 3 — Сменить статус
```bash
curl -s -X PATCH "http://localhost:8082/api/tasks/$TASK_ID/status" \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_PROGRESS"}' \
  | jq '{id, status}'
```
**Expected:** `"status":"IN_PROGRESS"`

### Step 4 — Обновить описание
```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X PUT "http://localhost:8082/api/tasks/$TASK_ID/description" \
  -H "Content-Type: text/plain" \
  --data-binary $'## Context\nblocked by release train'
```
**Expected:** `200`

### Step 5 — Добавить комментарий
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/timeline/comment" \
  -H "Content-Type: application/json" \
  -d '{"text":"Waiting for release manager input"}' \
  | jq '{eventType, summary}'
```
**Expected:** `"eventType":"COMMENT_ADDED"`

### Step 6 — Архивировать задачу
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/archive" \
  | jq '{id, status}'
```
**Expected:** `"status":"ARCHIVED"`

### Step 7 — Проверить полный timeline
```bash
curl -s "http://localhost:8082/api/tasks/$TASK_ID/timeline" \
  | jq '[.[].eventType]'
```
**Expected:** список содержит `TASK_CREATED`, `STATUS_CHANGED`, `DESCRIPTION_UPDATED`, `COMMENT_ADDED`, `TASK_ARCHIVED`

### Step 8 — Проверить, что архивная задача скрыта из обычного списка
```bash
curl -s "http://localhost:8082/api/tasks?date=$TODAY" \
  | jq "[.[] | select(.id == $TASK_ID)] | length"
```
**Expected:** `0`
