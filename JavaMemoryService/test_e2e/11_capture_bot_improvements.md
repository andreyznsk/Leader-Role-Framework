# Scenario: Capture Bot Improvements — context prompt and UI task conversion

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0 (CR-MEM-005)

## Описание
Проверить улучшения Capture Bot:
- UI `/ui/notes` показывает кнопку `→ В задачу`
- UI `/ui/risks` показывает кнопку `→ В задачу`
- существующий `POST /api/tasks/pending` принимает `dueDate`
- задача из заметки/риска появляется в `/ui/today` в секции PENDING
- `/api/context` содержит текущие задачи и открытые риски, которые используются
  Capture Bot prompt-ом как контекст дня

Полный клик по Bootstrap-модалке проверяется вручную в браузере. E2E через shell
проверяет тот же backend endpoint, который вызывается JavaScript-формой.

## Preconditions
- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432
- `jq` установлен

## Steps

### Step 1 — Создать note для UI /ui/notes
```bash
NOTE_TEXT="E2E: note convertible to task $(date +%s)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/notes \
  -H "Content-Type: application/json" \
  -d "{
    \"text\": \"$NOTE_TEXT\",
    \"tags\": \"e2e,task-convert\",
    \"source\": \"capture\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
NOTE_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | Note ID: $NOTE_ID"
```
**Expected:** HTTP 201
**Extract:** `id` → `$NOTE_ID`

### Step 2 — /ui/notes содержит кнопку "→ В задачу" и модалку
```bash
HTML=$(curl -s http://localhost:8082/ui/notes)
echo "$HTML" | grep -q 'taskFromItemModal' && \
echo "$HTML" | grep -q '&rarr; В задачу' && \
echo "$HTML" | grep -q "$NOTE_TEXT" && \
echo "notes task action OK"
```
**Expected:** вывод `notes task action OK`

### Step 3 — Создать risk для UI /ui/risks и context
```bash
RISK_TITLE="E2E: risk convertible to task $(date +%s)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/risks \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"$RISK_TITLE\",
    \"description\": \"Проверка CR-MEM-005: риск можно перенести в задачу\",
    \"probability\": \"MEDIUM\",
    \"impact\": \"HIGH\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
RISK_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | Risk ID: $RISK_ID"
```
**Expected:** HTTP 201, `"status":"OPEN"`
**Extract:** `id` → `$RISK_ID`

### Step 4 — /ui/risks содержит кнопку "→ В задачу" и модалку
```bash
HTML=$(curl -s http://localhost:8082/ui/risks)
echo "$HTML" | grep -q 'taskFromRiskModal' && \
echo "$HTML" | grep -q '&rarr; В задачу' && \
echo "$HTML" | grep -q "$RISK_TITLE" && \
echo "risks task action OK"
```
**Expected:** вывод `risks task action OK`

### Step 5 — Создать PENDING задачу с dueDate тем же endpoint-ом, что использует модалка
```bash
DUE_DATE=$(date -d '+3 days' +%Y-%m-%d 2>/dev/null || python3 -c 'import datetime; print((datetime.date.today()+datetime.timedelta(days=3)).isoformat())')
TASK_TITLE="E2E: task converted from note $(date +%s)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"$TASK_TITLE\",
    \"description\": \"Задача создана через endpoint модалки CR-MEM-005\",
    \"priority\": \"NORMAL\",
    \"dueDate\": \"$DUE_DATE\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
TASK_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | Task ID: $TASK_ID | Due: $(echo "$BODY" | jq -r '.dueDate')"
```
**Expected:** HTTP 201, `"status":"PENDING"`, `"dueDate":"$DUE_DATE"`
**Extract:** `id` → `$TASK_ID`

### Step 6 — PENDING задача видна в /api/tasks/pending
```bash
curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.id == '$TASK_ID' and .title == "'$TASK_TITLE'" and .dueDate == "'$DUE_DATE'")] | length'
```
**Expected:** результат `1`

### Step 7 — /ui/today содержит созданную PENDING задачу
```bash
curl -s http://localhost:8082/ui/today | grep -q "$TASK_TITLE" && echo "today pending UI OK"
```
**Expected:** вывод `today pending UI OK`

### Step 8 — /api/context содержит открытый риск для prompt-контекста
```bash
curl -s http://localhost:8082/api/context \
  | jq '[.openRisks[]? | select(.id == '$RISK_ID' and .title == "'$RISK_TITLE'")] | length'
```
**Expected:** результат `1`

### Step 9 — /api/context содержит задачу в текущем контексте после confirm
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/confirm" > /dev/null
curl -s http://localhost:8082/api/context \
  | jq '[.todayPlan.tasks[]? | select(.id == '$TASK_ID' and .title == "'$TASK_TITLE'")] | length'
```
**Expected:** результат `1`

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/delete" > /dev/null 2>&1 || true
curl -s -X DELETE "http://localhost:8082/api/risks/$RISK_ID" > /dev/null 2>&1 || true
echo "Cleanup: task $TASK_ID deleted, risk $RISK_ID closed. Note $NOTE_ID remains as harmless E2E marker."
```
