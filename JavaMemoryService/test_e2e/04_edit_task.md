# Scenario: Edit Task

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Создать задачу, затем изменить title, priority, description через PUT /api/tasks/{id}.
Пройти полный жизненный цикл статусов: TODO → IN_PROGRESS → DONE.
Проверить что изменения сохраняются и возвращаются корректно.

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать задачу для редактирования
```bash
TODAY=$(date +%Y-%m-%d)
RESPONSE=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"E2E: Задача до редактирования\",
    \"date\": \"$TODAY\",
    \"priority\": \"LOW\",
    \"source\": \"MANUAL\"
  }")
TASK_ID=$(echo "$RESPONSE" | jq -r '.id')
echo "Created task: $TASK_ID"
```
**Expected:** HTTP 201, `"title":"E2E: Задача до редактирования"`, `"priority":"LOW"`
**Extract:** `id` → `$TASK_ID`

### Step 2 — Изменить title и priority через PUT
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT "http://localhost:8082/api/tasks/$TASK_ID" \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"E2E: Задача ПОСЛЕ редактирования\",
    \"priority\": \"HIGH\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE"
echo "$BODY" | jq '{id, title, priority, status}'
```
**Expected:** HTTP 200, `"title":"E2E: Задача ПОСЛЕ редактирования"`, `"priority":"HIGH"`, `"status":"TODO"`

### Step 3 — Изменения сохранились — перечитать задачу из плана
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$TODAY" \
  | jq ".[] | select(.id == $TASK_ID) | {id, title, priority}"
```
**Expected:** объект содержит `"title":"E2E: Задача ПОСЛЕ редактирования"` и `"priority":"HIGH"`

### Step 4 — Изменить статус: TODO → IN_PROGRESS через PATCH /api/tasks/{id}/status
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X PATCH "http://localhost:8082/api/tasks/$TASK_ID/status" \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_PROGRESS"}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE | Status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 200, `"status":"IN_PROGRESS"`

### Step 5 — Отметить задачу выполненной через POST /done
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "http://localhost:8082/api/tasks/$TASK_ID/done")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE | Status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 200, `"status":"DONE"`

### Step 6 — Выполненная задача не видна в фильтре TODO
```bash
TODAY=$(date +%Y-%m-%d)
RESULT=$(curl -s "http://localhost:8082/api/tasks?date=$TODAY&status=TODO" \
  | jq "[.[] | select(.id == $TASK_ID)] | length")
echo "Task in TODO list: $RESULT"
```
**Expected:** результат `0` — задача не в TODO

### Step 7 — Сохранить описание задачи через PUT /description
```bash
curl -s -w "\n%{http_code}" \
  -X PUT "http://localhost:8082/api/tasks/$TASK_ID/description" \
  -H "Content-Type: text/plain" \
  -d "## Контекст
E2E тест редактирования задачи.

## Что сделано
- изменён title
- изменён priority
- пройден полный цикл статусов"
```
**Expected:** HTTP 200

### Step 8 — Прочитать описание задачи
```bash
curl -s "http://localhost:8082/api/tasks/$TASK_ID/description"
```
**Expected:** HTTP 200, тело содержит `"E2E тест редактирования задачи"`

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/delete" > /dev/null
echo "Cleanup: task $TASK_ID deleted"
```
