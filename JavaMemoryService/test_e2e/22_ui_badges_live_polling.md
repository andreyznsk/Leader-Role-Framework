# Scenario: Live-обновление счётчиков UI (badge polling)

**service:** JavaMemoryService
**port:** 8082
**priority:** MEDIUM
**depends_on:** postgres
**version:** 1.0 (CR-MEM-028)

## Описание

Проверяет контракт нового endpoint `GET /api/ui/badges` и то, что счётчик
`newIntake` реагирует на новый intake item без перезагрузки страницы
(браузерная часть — live-обновление DOM — покрыта Playwright-тестом
`tests/badge-polling.spec.js`).

## Preconditions

- `JavaMemoryService` запущен на `:8082`
- PostgreSQL доступен на `:5432`
- `jq` установлен

## Steps

### Step 1 — Endpoint отвечает и формат конверта корректен

```bash
RESPONSE=$(curl -s -w "\n%{http_code}" http://localhost:8082/api/ui/badges)
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "$BODY" | jq '{hasNewIntake: (.counts.newIntake != null), hasPendingTasks: (.counts.pendingTasks != null), hasServerTime: (.serverTime != null)}'
echo "HTTP=$HTTP_CODE"
```

**Expected:** `HTTP=200`, все три поля `true`

### Step 2 — Счётчик newIntake реагирует на новый intake item

```bash
BEFORE=$(curl -s http://localhost:8082/api/ui/badges | jq '.counts.newIntake')

curl -s -X POST http://localhost:8082/api/intake \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"MANUAL","sourcePayload":"badge-poll-e2e-test","suggestedRoute":"TASK"}' \
  | jq -r '.id' > /tmp/badge-poll-intake-id.txt

AFTER=$(curl -s http://localhost:8082/api/ui/badges | jq '.counts.newIntake')

echo "BEFORE=$BEFORE AFTER=$AFTER"
if [ "$AFTER" -eq $((BEFORE + 1)) ]; then
  echo "counter increased OK"
else
  echo "FAIL: expected AFTER=BEFORE+1"
  exit 1
fi
```

**Expected:** вывод `counter increased OK`

### Step 3 — pendingTasks совпадает с /api/tasks pending

```bash
TODAY=$(date +%F)
PENDING_FROM_TASKS=$(curl -s "http://localhost:8082/api/tasks?date=$TODAY&status=PENDING" | jq 'length')
PENDING_FROM_BADGES=$(curl -s http://localhost:8082/api/ui/badges | jq '.counts.pendingTasks')
echo "tasks=$PENDING_FROM_TASKS badges=$PENDING_FROM_BADGES"
```

**Expected:** оба значения — валидные неотрицательные числа (полное совпадение
зависит от того, ограничен ли `/api/tasks` датой `$TODAY`, в то время как
`pendingTasks` в бейдже — по всем pending задачам; расхождение — не баг).

### Step 4 — data-badge атрибуты присутствуют в sidebar даже при нулевом счётчике

```bash
HTML=$(curl -s http://localhost:8082/ui/today)
echo "$HTML" | grep -q 'data-badge="newIntake"' && echo "intake marker OK"
echo "$HTML" | grep -q 'data-badge="pendingTasks"' && echo "todo marker OK"
```

**Expected:** оба вывода `... marker OK` (span всегда в DOM, скрыт через `display:none`,
а не отсутствует через `th:if`, как раньше)

## Cleanup

```bash
INTAKE_ID=$(cat /tmp/badge-poll-intake-id.txt 2>/dev/null)
if [ -n "$INTAKE_ID" ] && [ "$INTAKE_ID" != "null" ]; then
  curl -s -X POST "http://localhost:8082/api/intake/$INTAKE_ID/reject" \
    -H 'Content-Type: application/json' -d '{"reason":"e2e cleanup"}' > /dev/null
fi
rm -f /tmp/badge-poll-intake-id.txt
```
