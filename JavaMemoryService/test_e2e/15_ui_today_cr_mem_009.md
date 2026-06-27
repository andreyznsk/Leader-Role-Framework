# Scenario: UI Today — clickable task title and editor links (CR-MEM-009)

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Smoke-проверка HTML-контракта CR-MEM-009:
- `/ui/today` содержит CSS-класс `task-title-clickable` на названиях задач.
- `/ui/tasks/{id}/edit` содержит контейнер `task-editor-links` и атрибуты `target="_blank"` и `rel="noopener noreferrer"`.

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать задачу с URL в описании
```bash
TODAY=$(date +%Y-%m-%d)
RESPONSE=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{
    \"title\": \"E2E MEM-009: кликабельное название\",
    \"description\": \"Ссылки: https://jira.example/browse/ABC-123 и https://confluence.example/display/TEAM/Page?mode=edit#section\",
    \"date\": \"$TODAY\",
    \"priority\": \"HIGH\",
    \"source\": \"MANUAL\"
  }")
TASK_ID=$(echo "$RESPONSE" | jq -r '.id')
echo "Created task: $TASK_ID"
```
**Expected:** HTTP 201, поле `id` присутствует
**Extract:** `id` → `$TASK_ID`

### Step 2 — /ui/today отдаёт 200 и содержит class task-title-clickable
```bash
HTML=$(curl -s http://localhost:8082/ui/today)
echo "$HTML" | grep -o 'task-title-clickable' | head -1
```
**Expected:** вывод содержит `task-title-clickable`

### Step 3 — Названия задач в pending-секции и текущих задачах являются ссылками с task-title-clickable
```bash
HTML=$(curl -s http://localhost:8082/ui/today)
echo "$HTML" | grep -c 'task-title-clickable'
```
**Expected:** число >= 1 (минимум одна задача с кликабельным названием)

### Step 4 — /ui/tasks/{id}/edit отдаёт 200
```bash
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8082/ui/tasks/$TASK_ID/edit"
```
**Expected:** HTTP 200

### Step 5 — Редактор содержит контейнер task-editor-links
```bash
curl -s "http://localhost:8082/ui/tasks/$TASK_ID/edit" | grep -o 'task-editor-links'
```
**Expected:** вывод содержит `task-editor-links`

### Step 6 — Редактор содержит атрибуты безопасных ссылок
```bash
curl -s "http://localhost:8082/ui/tasks/$TASK_ID/edit" | grep -o 'noopener noreferrer'
```
**Expected:** вывод содержит `noopener noreferrer`

### Step 7 — XSS-защита: script-тег в описании не попадает в HTML как исполняемый
```bash
XSS_RESPONSE=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\": \"E2E MEM-009: XSS check\", \"description\": \"<script>alert(1)<\\/script>\", \"date\": \"$(date +%Y-%m-%d)\", \"source\": \"MANUAL\"}")
XSS_ID=$(echo "$XSS_RESPONSE" | jq -r '.id')
EDIT_HTML=$(curl -s "http://localhost:8082/ui/tasks/$XSS_ID/edit")
echo "$EDIT_HTML" | grep -c '<script>alert'
```
**Expected:** результат `0` — тег `<script>` не вставлен в HTML без экранирования

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/delete" > /dev/null
curl -s -X POST "http://localhost:8082/api/tasks/$XSS_ID/delete" > /dev/null
echo "Cleanup: tasks $TASK_ID, $XSS_ID deleted"
```
