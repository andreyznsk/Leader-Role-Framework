# Scenario: Task Links (relates to / blocks / duplicates / parent)

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Проверяет CR-MEM-031: направленные связи между задачами (`RELATES_TO`, `BLOCKS`,
`DUPLICATES`, `PARENT_OF`), зеркальное чтение обратной связи (`BLOCKS` ↔ `BLOCKED_BY`),
запрет self-link, запрет точного дубля, удаление связи, а также proposal-flow через
Intake Gateway (`proposeTaskLink` → `/ui/intake` → Apply).

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать задачи A и B
```bash
TODAY=$(date +%Y-%m-%d)
TASK_A=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\": \"E2E Link Task A\", \"date\": \"$TODAY\", \"priority\": \"NORMAL\", \"source\": \"MANUAL\"}" | jq -r '.id')
TASK_B=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\": \"E2E Link Task B\", \"date\": \"$TODAY\", \"priority\": \"NORMAL\", \"source\": \"MANUAL\"}" | jq -r '.id')
echo "A=$TASK_A B=$TASK_B"
```
**Expected:** оба `id` не пустые

### Step 2 — Создать связь A → B (BLOCKS)
```bash
RESP=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8082/api/tasks/$TASK_A/links" \
  -H "Content-Type: application/json" \
  -d "{\"toTaskId\": $TASK_B, \"linkType\": \"BLOCKS\"}")
HTTP_CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
LINK_ID=$(echo "$BODY" | jq -r '.id')
echo "$BODY" | jq '{id, direction, linkType, relatedTaskId}'
echo "HTTP: $HTTP_CODE"
```
**Expected:** `HTTP: 201`, `"direction":"OUT"`, `"linkType":"BLOCKS"`, `"relatedTaskId":<TASK_B>`

### Step 3 — Прочитать связи у A (OUT) и у B (зеркальная IN)
```bash
curl -s "http://localhost:8082/api/tasks/$TASK_A/links" | jq '.[0] | {direction, linkType, relatedTaskId}'
curl -s "http://localhost:8082/api/tasks/$TASK_B/links" | jq '.[0] | {direction, linkType, relatedTaskId}'
```
**Expected:** у A `direction=OUT, linkType=BLOCKS, relatedTaskId=B`; у B `direction=IN, linkType=BLOCKED_BY, relatedTaskId=A`

### Step 4 — Повторный POST того же линка → 409
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8082/api/tasks/$TASK_A/links" \
  -H "Content-Type: application/json" \
  -d "{\"toTaskId\": $TASK_B, \"linkType\": \"BLOCKS\"}"
```
**Expected:** `409`

### Step 5 — Self-link A → A → 400
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8082/api/tasks/$TASK_A/links" \
  -H "Content-Type: application/json" \
  -d "{\"toTaskId\": $TASK_A, \"linkType\": \"RELATES_TO\"}"
```
**Expected:** `400`

### Step 6 — MCP proposeTaskLink → карточка в /ui/intake → Apply → связь создана
```bash
TASK_C=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\": \"E2E Link Task C\", \"date\": \"$TODAY\", \"priority\": \"NORMAL\", \"source\": \"MANUAL\"}" | jq -r '.id')

INTAKE=$(curl -s -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{\"sourceType\":\"AGENT_MCP\",\"sourcePayload\":{\"tool\":\"proposeTaskLink\",\"fromTaskId\":$TASK_A,\"toTaskId\":$TASK_C,\"linkType\":\"RELATES_TO\"},\"suggestedRoute\":\"TASK_LINK\",\"suggestedPayload\":{\"fromTaskId\":$TASK_A,\"toTaskId\":$TASK_C,\"linkType\":\"RELATES_TO\"},\"createdBy\":\"agent-mcp\"}")
INTAKE_ID=$(echo "$INTAKE" | jq -r '.id')
echo "$INTAKE" | jq '{id, status, suggestedRoute}'

APPLIED=$(curl -s -X POST "http://localhost:8082/api/intake/$INTAKE_ID/apply" \
  -H "Content-Type: application/json" -d '{}')
echo "$APPLIED" | jq '{status, finalRoute}'

curl -s "http://localhost:8082/api/tasks/$TASK_A/links" | jq 'map(select(.relatedTaskId == '"$TASK_C"')) | length'
```
**Expected:** intake `status: NEW` → after apply `status: APPLIED`, `finalRoute: TASK_LINK`; связь A→C появилась (`length` = `1`)

### Step 7 — Удалить связь
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "http://localhost:8082/api/tasks/$TASK_A/links/$LINK_ID"
curl -s "http://localhost:8082/api/tasks/$TASK_A/links" | jq 'length'
```
**Expected:** `204`, затем `1` (осталась только связь с C из шага 6)

## Известное ограничение
Как и в CR-MEM-030, каскадное удаление связей на уровне БД (`ON DELETE CASCADE`) сработает
только при жёстком `DELETE` задачи, которого в приложении нет (только soft `archive`).
Поэтому "DELETE задачи → связь исчезла у другой стороны" из CR-текста не тестируется как
самостоятельный шаг — это сознательное отклонение, аналогичное CR-MEM-030.

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_A/archive" > /dev/null
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_B/archive" > /dev/null
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_C/archive" > /dev/null
```
