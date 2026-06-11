# Scenario: Health Check

**service:** JavaMemoryService
**port:** 8082
**priority:** CRITICAL
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432

## Steps

### Step 1 — Actuator health возвращает 200
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health
```
**Expected:** HTTP 200

### Step 2 — Статус UP
```bash
curl -s http://localhost:8082/actuator/health
```
**Expected:** тело содержит `"status":"UP"`

## Cleanup
# ничего не требуется
