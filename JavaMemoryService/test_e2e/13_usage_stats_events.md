# Scenario: Usage Statistics — events and aggregate API

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0

## Описание
Проверить, что Memory Service принимает usage events через local/debug API и отдаёт агрегированную статистику.

## Preconditions
- JavaMemoryService запущен на :8082
- Профиль запуска включает `local`
- `jq` установлен

## Steps

### Step 1 — Проверить health
```bash
curl -s http://localhost:8082/actuator/health | jq -r '.status'
```
**Expected:** результат `UP`

### Step 2 — Записать RAG usage event
```bash
RUN_ID="e2e-stats-$(date +%s)"
curl -s -o /tmp/usage-event-rag.out -w "%{http_code}" \
  -X POST http://localhost:8082/api/stats/events \
  -H "Content-Type: application/json" \
  -d "{\"eventType\":\"RAG_SEARCH\",\"source\":\"test\",\"status\":\"SUCCESS\",\"metadata\":{\"query\":\"$RUN_ID\",\"topK\":5,\"resultsCount\":1}}"
```
**Expected:** HTTP code `201`

### Step 3 — Записать saved-time usage event
```bash
curl -s -o /tmp/usage-event-used.out -w "%{http_code}" \
  -X POST http://localhost:8082/api/stats/events \
  -H "Content-Type: application/json" \
  -d "{\"eventType\":\"RAG_RESULT_USED\",\"source\":\"test\",\"status\":\"SUCCESS\",\"metadata\":{\"query\":\"$RUN_ID\"}}"
```
**Expected:** HTTP code `201`

### Step 4 — Получить агрегированную статистику
```bash
STATS=$(curl -s "http://localhost:8082/api/stats/usage?period=7d")
echo "$STATS" | jq
echo "$STATS" | jq -e '.ragSearches >= 1 and .savedMinutes > 0 and (.eventsBySource.test >= 1)'
```
**Expected:** jq-проверка завершается успешно

