# Scenario: Agent MCP write-tools route through Intake Gateway

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0 (CR-MEM-024)

## Описание

Проверяет, что agent-facing MCP write tools больше не создают operational сущности напрямую:

- `proposeTask` создаёт `intake_items` со `sourceType = AGENT_MCP`
- apply task proposal создаёт задачу только после `/api/intake/{id}/apply`
- `proposeRisk` и `proposeIncident` создают proposal в intake

## Preconditions

- `JavaMemoryService` запущен на `:8082`
- PostgreSQL доступен на `:5432`
- `jq` установлен

## Steps

### Step 1 — Создать task proposal в intake

```bash
RUN_ID="agent-mcp-$(date +%s)"
TODAY=$(date +%F)
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceType\": \"AGENT_MCP\",
    \"sourceId\": \"$RUN_ID-task\",
    \"sourcePayload\": {
      \"tool\": \"proposeTask\",
      \"title\": \"$RUN_ID rollout plan\",
      \"date\": \"$TODAY\",
      \"priority\": \"HIGH\"
    },
    \"agentProvider\": \"codex\",
    \"suggestedRoute\": \"TASK\",
    \"suggestedPayload\": {
      \"title\": \"$RUN_ID rollout plan\",
      \"description\": \"$RUN_ID task proposal via intake\",
      \"date\": \"$TODAY\",
      \"priority\": \"HIGH\"
    },
    \"createdBy\": \"agent-mcp\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
TASK_INTAKE_ID=$(echo "$BODY" | jq -r '.id')
TASK_STATUS=$(echo "$BODY" | jq -r '.status')
TASK_ROUTE=$(echo "$BODY" | jq -r '.suggestedRoute')
echo "HTTP=$HTTP_CODE | ID=$TASK_INTAKE_ID | STATUS=$TASK_STATUS | ROUTE=$TASK_ROUTE"
```

**Expected:** HTTP `201`, `status = NEW`, `suggestedRoute = TASK`

### Step 2 — Intake queue содержит task proposal

```bash
curl -s "http://localhost:8082/api/intake?status=NEW&sourceType=AGENT_MCP&suggestedRoute=TASK" \
  | jq --arg id "$TASK_INTAKE_ID" '[.[] | select(.id == $id and .createdBy == "agent-mcp")] | length'
```

**Expected:** результат `1`

### Step 3 — До apply задачи ещё нет в operational tasks

```bash
curl -s "http://localhost:8082/api/tasks?date=$TODAY" \
  | jq --arg title "$RUN_ID rollout plan" '[.[] | select(.title == $title)] | length'
```

**Expected:** результат `0`

### Step 4 — Apply task proposal создаёт задачу

```bash
curl -s -X POST "http://localhost:8082/api/intake/$TASK_INTAKE_ID/apply" \
  -H "Content-Type: application/json" \
  -d '{"reviewedBy":"e2e"}' | jq '{status, finalRoute}'

curl -s "http://localhost:8082/api/tasks?date=$TODAY" \
  | jq --arg title "$RUN_ID rollout plan" '[.[] | select(.title == $title)] | length'
```

**Expected:** apply response has `status = APPLIED`, второй запрос возвращает `1`

### Step 5 — Создать risk proposal

```bash
curl -s -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceType\": \"AGENT_MCP\",
    \"sourceId\": \"$RUN_ID-risk\",
    \"sourcePayload\": {
      \"tool\": \"proposeRisk\",
      \"title\": \"$RUN_ID single deploy owner\"
    },
    \"agentProvider\": \"codex\",
    \"suggestedRoute\": \"RISK\",
    \"suggestedPayload\": {
      \"title\": \"$RUN_ID single deploy owner\",
      \"description\": \"Only one person knows deploy flow\",
      \"probability\": \"MEDIUM\",
      \"impact\": \"HIGH\"
    },
    \"createdBy\": \"agent-mcp\"
  }" | jq -r '.suggestedRoute'
```

**Expected:** вывод `RISK`

### Step 6 — Создать incident proposal

```bash
curl -s -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceType\": \"AGENT_MCP\",
    \"sourceId\": \"$RUN_ID-incident\",
    \"sourcePayload\": {
      \"tool\": \"proposeIncident\",
      \"title\": \"$RUN_ID db saturation\"
    },
    \"agentProvider\": \"codex\",
    \"suggestedRoute\": \"INCIDENT\",
    \"suggestedPayload\": {
      \"title\": \"$RUN_ID db saturation\",
      \"description\": \"Database connections exhausted\",
      \"severity\": \"P1\"
    },
    \"createdBy\": \"agent-mcp\"
  }" | jq -r '.suggestedRoute'
```

**Expected:** вывод `INCIDENT`
