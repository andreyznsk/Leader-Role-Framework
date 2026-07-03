# Scenario: Intake Gateway — manual review, reroute, apply and reject

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0 (CR-MEM-019)

## Описание

Проверяет новый ручной Intake Gateway:

- `POST /api/intake` создаёт item со статусом `NEW`
- `/ui/intake` показывает source payload, `agentPrompt`, `agentResult`, `suggestedRoute`
- route можно поменять перед apply
- apply в `NOTE` создаёт operational note
- apply в `RAG` создаёт markdown-кандидат только после ручного apply
- reject переводит item в `REJECTED`

Сценарий не требует поднятого `JavaRagService`: для ветки `RAG` проверяется факт
создания markdown-файла в `rag-inbox/intake`, а также то, что до apply файла не было.

## Preconditions

- `JavaMemoryService` запущен на `:8082`
- PostgreSQL доступен на `:5432`
- `jq` установлен
- директория `JavaRagService/rag-inbox` доступна из workspace

## Steps

### Step 1 — Создать intake item с suggestedRoute=RAG

```bash
RUN_ID="intake-e2e-$(date +%s)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceType\": \"MANUAL\",
    \"sourceId\": \"$RUN_ID-manual-rag\",
    \"sourcePayload\": {
      \"text\": \"$RUN_ID raw knowledge payload\",
      \"subject\": \"$RUN_ID release rule\"
    },
    \"agentProvider\": \"mock\",
    \"agentPrompt\": \"Prompt: classify $RUN_ID as RAG candidate\",
    \"agentResult\": {
      \"route\": \"RAG\",
      \"reason\": \"knowledge-like content\"
    },
    \"suggestedRoute\": \"RAG\",
    \"suggestedPayload\": {
      \"docType\": \"RAG\",
      \"title\": \"$RUN_ID release rule\",
      \"body\": \"$RUN_ID release process should go through calendar\"
    },
    \"confidence\": 0.91
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
INTAKE_RAG_ID=$(echo "$BODY" | jq -r '.id')
INTAKE_RAG_STATUS=$(echo "$BODY" | jq -r '.status')
echo "HTTP=$HTTP_CODE | ID=$INTAKE_RAG_ID | STATUS=$INTAKE_RAG_STATUS"
```

**Expected:** HTTP `201`, `status = NEW`

### Step 2 — Очередь `/api/intake?status=NEW` содержит item

```bash
curl -s "http://localhost:8082/api/intake?status=NEW" \
  | jq --arg id "$INTAKE_RAG_ID" '[.[] | select(.id == $id)] | length'
```

**Expected:** результат `1`

### Step 3 — `/ui/intake` показывает payload, prompt, result и suggested route

```bash
HTML=$(curl -s "http://localhost:8082/ui/intake?status=NEW")
echo "$HTML" | grep -q "Intake Gateway" && \
echo "$HTML" | grep -q "$RUN_ID raw knowledge payload" && \
echo "$HTML" | grep -q "Agent prompt" && \
echo "$HTML" | grep -q "Prompt: classify $RUN_ID as RAG candidate" && \
echo "$HTML" | grep -q "Agent result" && \
echo "$HTML" | grep -q "$RUN_ID release rule" && \
echo "intake ui OK"
```

**Expected:** вывод `intake ui OK`

### Step 4 — До apply в RAG ничего не создано

```bash
find JavaRagService/rag-inbox/intake -type f -name "*$INTAKE_RAG_ID*.md" 2>/dev/null | wc -l
```

**Expected:** результат `0`

### Step 5 — Применить item в RAG

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8082/api/intake/$INTAKE_RAG_ID/apply" \
  -H "Content-Type: application/json" \
  -d "{
    \"finalRoute\": \"RAG\",
    \"finalPayload\": {
      \"docType\": \"RAG\",
      \"title\": \"$RUN_ID release rule\",
      \"body\": \"$RUN_ID release process should go through calendar\",
      \"subject\": \"$RUN_ID release rule\",
      \"sender\": \"manual-e2e\"
    }
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
APPLIED_STATUS=$(echo "$BODY" | jq -r '.status')
FINAL_ROUTE=$(echo "$BODY" | jq -r '.finalRoute')
echo "HTTP=$HTTP_CODE | STATUS=$APPLIED_STATUS | ROUTE=$FINAL_ROUTE"
```

**Expected:** HTTP `200`, `status = APPLIED`, `finalRoute = RAG`

### Step 6 — После apply появился markdown-файл в `rag-inbox/intake`

```bash
RAG_FILE=$(find JavaRagService/rag-inbox/intake -type f -name "*$INTAKE_RAG_ID*.md" 2>/dev/null | head -1)
echo "RAG_FILE=${RAG_FILE:-not-found}"
[ -n "$RAG_FILE" ] && grep -q "$RUN_ID release rule" "$RAG_FILE" && echo "rag apply OK"
```

**Expected:** найден файл, вывод `rag apply OK`

### Step 7 — Создать второй item и вручную поменять route на NOTE

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceType\": \"MANUAL\",
    \"sourceId\": \"$RUN_ID-manual-reroute\",
    \"sourcePayload\": {
      \"text\": \"$RUN_ID content initially suggested as task\"
    },
    \"agentProvider\": \"mock\",
    \"agentPrompt\": \"Prompt: classify $RUN_ID as TASK candidate\",
    \"agentResult\": {
      \"route\": \"TASK\",
      \"reason\": \"contains action words\"
    },
    \"suggestedRoute\": \"TASK\",
    \"suggestedPayload\": {
      \"title\": \"$RUN_ID draft task\",
      \"description\": \"$RUN_ID task-like description\",
      \"priority\": \"HIGH\"
    },
    \"confidence\": 0.73
  }")
BODY=$(echo "$RESPONSE" | head -n -1)
INTAKE_NOTE_ID=$(echo "$BODY" | jq -r '.id')
echo "INTAKE_NOTE_ID=$INTAKE_NOTE_ID"
```

**Expected:** `INTAKE_NOTE_ID` не пустой

### Step 8 — Сохранить изменения: сменить route на NOTE и payload

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT "http://localhost:8082/api/intake/$INTAKE_NOTE_ID" \
  -H "Content-Type: application/json" \
  -d "{
    \"finalRoute\": \"NOTE\",
    \"finalPayload\": {
      \"title\": \"$RUN_ID rerouted note\",
      \"text\": \"$RUN_ID reviewer changed route from TASK to NOTE\",
      \"tags\": \"e2e,intake\"
    }
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
UPDATED_STATUS=$(echo "$BODY" | jq -r '.status')
UPDATED_ROUTE=$(echo "$BODY" | jq -r '.finalRoute')
echo "HTTP=$HTTP_CODE | STATUS=$UPDATED_STATUS | ROUTE=$UPDATED_ROUTE"
```

**Expected:** HTTP `200`, `status = REVIEWING`, `finalRoute = NOTE`

### Step 9 — Apply после reroute создаёт note

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8082/api/intake/$INTAKE_NOTE_ID/apply" \
  -H "Content-Type: application/json" \
  -d "{
    \"finalRoute\": \"NOTE\",
    \"finalPayload\": {
      \"title\": \"$RUN_ID rerouted note\",
      \"text\": \"$RUN_ID reviewer changed route from TASK to NOTE\",
      \"tags\": \"e2e,intake\"
    }
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
NOTE_APPLIED_STATUS=$(echo "$BODY" | jq -r '.status')
echo "HTTP=$HTTP_CODE | STATUS=$NOTE_APPLIED_STATUS"
```

**Expected:** HTTP `200`, `status = APPLIED`

### Step 10 — Note действительно создана

```bash
curl -s "http://localhost:8082/api/notes?limit=200" \
  | jq --arg title "$RUN_ID rerouted note" '[.[] | select(.title == $title)] | length'
```

**Expected:** результат `1`

### Step 11 — Создать и отклонить третий item

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceType\": \"MANUAL\",
    \"sourceId\": \"$RUN_ID-manual-noise\",
    \"sourcePayload\": {
      \"text\": \"$RUN_ID noisy content\"
    },
    \"suggestedRoute\": \"NOISE\",
    \"suggestedPayload\": {
      \"text\": \"$RUN_ID noisy content\"
    }
  }")
BODY=$(echo "$RESPONSE" | head -n -1)
INTAKE_REJECT_ID=$(echo "$BODY" | jq -r '.id')

RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8082/api/intake/$INTAKE_REJECT_ID/reject" \
  -H "Content-Type: application/json" \
  -d '{"reason":"noise"}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
REJECT_STATUS=$(echo "$BODY" | jq -r '.status')
REJECT_REASON=$(echo "$BODY" | jq -r '.rejectReason')
echo "HTTP=$HTTP_CODE | STATUS=$REJECT_STATUS | REASON=$REJECT_REASON"
```

**Expected:** HTTP `200`, `status = REJECTED`, `rejectReason = noise`

## Cleanup

```bash
echo "Cleanup note: $RUN_ID artifacts remain as E2E markers in intake/note/rag-inbox."
```
