# Scenario: Risks — создать, обновить митигацию, закрыть

**service:** JavaMemoryService
**port:** 8082
**priority:** MEDIUM
**depends_on:** postgres
**version:** 2.0 (data isolation fix)

## Описание
Создать операционный риск → проверить по ID → добавить митигацию → MITIGATED.
Все count-проверки по конкретному ID, не по длине списка.

## Steps

### Step 1 — Создать риск HIGH/HIGH
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/risks \
  -H "Content-Type: application/json" \
  -d '{
    "title": "E2E: Только один человек знает деплой prod",
    "description": "Bus factor = 1 для payments deployment",
    "probability": "HIGH",
    "impact": "HIGH"
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
RISK_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | ID: $RISK_ID | Status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 201, `"status":"OPEN"`, `"probability":"HIGH"`, `"impact":"HIGH"`
**Extract:** `id` → `$RISK_ID`

### Step 2 — Риск виден в списке открытых (проверка по ID)
```bash
curl -s "http://localhost:8082/api/risks?status=OPEN" \
  | jq '[.[] | select(.id == '$RISK_ID')] | length'
```
**Expected:** результат `1`

### Step 3 — Риск входит в getContext (проверка по ID)
```bash
curl -s http://localhost:8082/api/context \
  | jq '[.openRisks[]? | select(.id == '$RISK_ID')] | length'
```
**Expected:** результат `1`

### Step 4 — Добавить митигацию и изменить статус на MITIGATED
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT "http://localhost:8082/api/risks/$RISK_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "E2E: Только один человек знает деплой prod",
    "probability": "HIGH",
    "impact": "HIGH",
    "status": "MITIGATED",
    "mitigation": "Провели knowledge transfer. Добавили runbook в Confluence."
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE | Status: $(echo "$BODY" | jq -r '.status') | Mitigation: $(echo "$BODY" | jq -r '.mitigation // "null"')"
```
**Expected:** HTTP 200, `"status":"MITIGATED"`, поле `mitigation` заполнено

### Step 5 — MITIGATED риск НЕ в списке OPEN
```bash
curl -s "http://localhost:8082/api/risks?status=OPEN" \
  | jq '[.[] | select(.id == '$RISK_ID')] | length'
```
**Expected:** результат `0`

### Step 6 — UI /ui/risks доступна
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/ui/risks
```
**Expected:** HTTP 200

## Cleanup
```bash
curl -s -X DELETE "http://localhost:8082/api/risks/$RISK_ID" > /dev/null 2>&1 || \
  echo "DELETE not yet implemented — risk $RISK_ID stays as MITIGATED (harmless)"
```
