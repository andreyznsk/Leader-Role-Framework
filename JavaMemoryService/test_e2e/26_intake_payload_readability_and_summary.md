# Scenario: Intake payload readability and mail summary

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание

Проверяет CR-MEM-033:

- `Original Payload` в `/ui/intake` показывается в компактном виде без шума от `\n`, `\r`, CR/LF и похожих escape-последовательностей;
- `MAIL`-derived item получает заполненный `finalPayload`, совместимый с текущим контрактом;
- `Final Payload` для `TASK` содержит осмысленную summary исходного письма и может быть применён без повторного чтения всего email.

## Preconditions

- `JavaMemoryService` запущен на `:8082`
- PostgreSQL доступен на `:5432`
- `jq` установлен

## Steps

### Step 1 — Создать mail-derived intake item с реальными и escaped переносами

```bash
RUN_ID="intake-readable-$(date +%s)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/intake \
  -H "Content-Type: application/json" \
  -d "{
    \"sourceType\": \"MAIL\",
    \"sourceId\": \"$RUN_ID-message-id\",
    \"sourcePayload\": {
      \"from\": \"owner@example.com\",
      \"subject\": \"$RUN_ID pipeline follow-up\",
      \"body\": \"Line 1\\nLine 2\\\\nLine 3\\r\\\\r\\\\m https://tracker.local/$RUN_ID TASK-42 screenshot.png\"
    },
    \"suggestedRoute\": \"TASK\",
    \"suggestedPayload\": {
      \"title\": \"$RUN_ID check pipeline\",
      \"description\": \"## Mail intake summary\n- Initiator: owner@example.com\n- Requested action: check pipeline\n- Context: verify release readiness\n- Deadline/date: 2026-07-10\n- Links/tickets/artifacts: https://tracker.local/$RUN_ID, TASK-42, screenshot.png\n- Expected result: confirm readiness and send reply\n- Suggested route: TASK\n- Source subject: $RUN_ID pipeline follow-up\",
      \"emailId\": \"$RUN_ID-message-id\",
      \"sender\": \"owner@example.com\",
      \"priority\": \"HIGH\",
      \"subject\": \"$RUN_ID pipeline follow-up\",
      \"sourceSummary\": {
        \"initiator\": \"owner@example.com\",
        \"requestedAction\": \"check pipeline\",
        \"context\": \"verify release readiness\",
        \"deadline\": \"2026-07-10\",
        \"artifacts\": [\"https://tracker.local/$RUN_ID\", \"TASK-42\", \"screenshot.png\"],
        \"expectedResult\": \"confirm readiness and send reply\",
        \"suggestedRoute\": \"TASK\"
      }
    },
    \"createdBy\": \"mail-agent\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
INTAKE_ID=$(echo "$BODY" | jq -r '.id')
FINAL_TITLE=$(echo "$BODY" | jq -r '.finalPayload.title')
echo "HTTP=$HTTP_CODE ID=$INTAKE_ID FINAL_TITLE=$FINAL_TITLE"
```

**Expected:** HTTP `201`, `INTAKE_ID` не пустой, `finalPayload.title` равен `$RUN_ID check pipeline`

### Step 2 — API сохраняет raw source payload без display-нормализации

```bash
curl -s "http://localhost:8082/api/intake/$INTAKE_ID" | jq -r '.sourcePayload.body'
```

**Expected:** в значении остаются escaped последовательности (`\n`, `\r`, `\m`) и исходная структура payload не меняется

### Step 3 — `/ui/intake` открывается и содержит оба блока

```bash
HTML=$(curl -s "http://localhost:8082/ui/intake?status=NEW&sourceType=MAIL")
echo "$HTML" | grep -q "Original payload" && \
echo "$HTML" | grep -q "Final payload" && \
echo "blocks present"
```

**Expected:** вывод `blocks present`

### Step 4 — HTML для Original Payload не содержит шумных переносов из mail body

```bash
echo "$HTML" | grep -q "Line 1 Line 2 Line 3 https://tracker.local/$RUN_ID TASK-42 screenshot.png" && \
! echo "$HTML" | grep -q '\\\\n' && \
! echo "$HTML" | grep -q '\\\\r' && \
echo "normalized original payload OK"
```

**Expected:** вывод `normalized original payload OK`

### Step 5 — Final Payload уже заполнен summary и пригоден для apply

```bash
curl -s "http://localhost:8082/api/intake/$INTAKE_ID" \
  | jq '{finalRoute, finalPayload: {title, description, sourceSummary}}'
```

**Expected:** `finalPayload.description` содержит секции `Initiator`, `Requested action`, `Context`, `Deadline/date`, `Links/tickets/artifacts`, `Expected result`; `sourceSummary` присутствует

### Step 6 — Apply создаёт meaningful task

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8082/api/intake/$INTAKE_ID/apply" \
  -H "Content-Type: application/json" \
  -d '{}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
STATUS=$(echo "$BODY" | jq -r '.status')
FINAL_ROUTE=$(echo "$BODY" | jq -r '.finalRoute')
echo "HTTP=$HTTP_CODE STATUS=$STATUS FINAL_ROUTE=$FINAL_ROUTE"
```

**Expected:** HTTP `200`, `STATUS=APPLIED`, `FINAL_ROUTE=TASK`

### Step 7 — Созданная задача содержит summary из final payload

```bash
curl -s "http://localhost:8082/api/tasks?includeDone=true&limit=200" \
  | jq --arg title "$RUN_ID check pipeline" '[.[] | select(.title == $title)] | .[0] | {title, description}'
```

**Expected:** задача найдена; `description` содержит `Mail intake summary` и summary-поля из Step 5
