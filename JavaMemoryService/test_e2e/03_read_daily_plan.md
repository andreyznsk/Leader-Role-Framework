# Scenario: Read Daily Plan

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Создать несколько задач на сегодня с разными приоритетами.
Проверить план дня через GET /api/tasks?date=... и GET /api/context.
Убедиться что задачи возвращаются в правильном порядке (sort_order).
Проверить UI-страницу /ui/today.

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать задачу с приоритетом CRITICAL
```bash
TODAY=$(date +%Y-%m-%d)
RESPONSE=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"E2E: Критическая задача\",\"date\":\"$TODAY\",\"priority\":\"CRITICAL\",\"source\":\"MANUAL\"}")
TASK_ID_1=$(echo "$RESPONSE" | jq -r '.id')
echo "Task 1 ID: $TASK_ID_1"
```
**Expected:** HTTP 201, `"priority":"CRITICAL"`
**Extract:** `id` → `$TASK_ID_1`

### Step 2 — Создать задачу с приоритетом NORMAL
```bash
TODAY=$(date +%Y-%m-%d)
RESPONSE=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"E2E: Обычная задача\",\"date\":\"$TODAY\",\"priority\":\"NORMAL\",\"source\":\"MANUAL\"}")
TASK_ID_2=$(echo "$RESPONSE" | jq -r '.id')
echo "Task 2 ID: $TASK_ID_2"
```
**Expected:** HTTP 201, `"priority":"NORMAL"`
**Extract:** `id` → `$TASK_ID_2`

### Step 3 — Получить план дня — обе задачи присутствуют
```bash
TODAY=$(date +%Y-%m-%d)
BODY=$(curl -s "http://localhost:8082/api/tasks?date=$TODAY")
echo "$BODY" | jq 'length'
echo "$BODY" | jq '[.[] | {id, title, priority, status}]'
```
**Expected:** HTTP 200, массив содержит `"E2E: Критическая задача"` и `"E2E: Обычная задача"`

### Step 4 — Фильтрация по статусу работает
```bash
TODAY=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$TODAY&status=TODO" | jq 'length'
```
**Expected:** HTTP 200, длина массива >= 2

### Step 5 — GET /api/context содержит задачи сегодня
```bash
curl -s http://localhost:8082/api/context
```
**Expected:** HTTP 200, тело содержит `"E2E: Критическая задача"`

### Step 6 — UI /ui/today отдаёт страницу
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/ui/today
```
**Expected:** HTTP 200

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID_1/delete" > /dev/null
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID_2/delete" > /dev/null
echo "Cleanup: tasks $TASK_ID_1, $TASK_ID_2 deleted"
```
