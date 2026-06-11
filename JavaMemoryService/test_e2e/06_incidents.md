# Scenario: Incidents — создать, обновить, закрыть

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 2.0 (data isolation fix)

## Описание
Полный жизненный цикл инцидента: создать → проверить по ID → resolve с root cause →
проверить что исчез из открытых. Все проверки count делаются по конкретному ID,
а не по длине списка — устойчиво к накопленным данным из прошлых прогонов.

## Steps

### Step 1 — Создать инцидент P1
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/incidents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "E2E: PROD деградация payments сервиса",
    "severity": "P1",
    "description": "Увеличение latency payments API до 30сек"
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
INC_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | ID: $INC_ID | Status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 201, `"status":"OPEN"`, `"severity":"P1"`
**Extract:** `id` → `$INC_ID`

### Step 2 — Инцидент виден в списке открытых (проверка по ID)
```bash
curl -s "http://localhost:8082/api/incidents?status=OPEN" \
  | jq '[.[] | select(.id == '$INC_ID')] | length'
```
**Expected:** результат `1`

### Step 3 — Обновить статус на INVESTIGATING
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT "http://localhost:8082/api/incidents/$INC_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "E2E: PROD деградация payments сервиса",
    "severity": "P1",
    "status": "INVESTIGATING",
    "description": "Нашли причину — OOM в pod payments-api"
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
echo "HTTP: $HTTP_CODE | Status: $(echo "$RESPONSE" | head -n -1 | jq -r '.status')"
```
**Expected:** HTTP 200, `"status":"INVESTIGATING"`

### Step 4 — Закрыть инцидент с root cause
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "http://localhost:8082/api/incidents/$INC_ID/resolve" \
  -H "Content-Type: application/json" \
  -d '{
    "rootCause": "OOM в pod payments-api из-за утечки памяти в batch-обработке",
    "actionItems": "1. Увеличить heap limit\n2. Добавить алерт на heap > 80%"
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE | Status: $(echo "$BODY" | jq -r '.status') | rootCause: $(echo "$BODY" | jq -r '.rootCause // "null"')"
```
**Expected:** HTTP 200, `"status":"RESOLVED"`, поле `rootCause` заполнено

### Step 5 — RESOLVED инцидент НЕ в списке OPEN
```bash
curl -s "http://localhost:8082/api/incidents?status=OPEN" \
  | jq '[.[] | select(.id == '$INC_ID')] | length'
```
**Expected:** результат `0`

### Step 6 — UI /ui/incidents доступна
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/ui/incidents
```
**Expected:** HTTP 200

### Step 7 — getContext не включает RESOLVED инцидент
```bash
curl -s http://localhost:8082/api/context \
  | jq '[.openIncidents[]? | select(.id == '$INC_ID')] | length'
```
**Expected:** результат `0`

## Cleanup
```bash
# Soft delete через статус CLOSED (требует CR-MEM-BUGFIX-006)
curl -s -X DELETE "http://localhost:8082/api/incidents/$INC_ID" > /dev/null 2>&1 || \
  echo "DELETE not yet implemented — incident $INC_ID stays as RESOLVED (harmless)"
```
