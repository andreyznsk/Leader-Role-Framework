# Scenario: Context Does Not Include Pending Tasks

**service:** JavaMemoryService
**port:** 8082
**priority:** MEDIUM
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать PENDING задачу
```bash
curl -s -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{"title":"Context Test Pending","emailId":"ctx-test-001","sender":"test@test.com","priority":"LOW"}'
```
**Expected:** HTTP 201
**Extract:** `id` → `$CTX_TASK_ID`

### Step 2 — getContext через MCP — PENDING задача НЕ входит
```bash
curl -s -X POST http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"getContext","arguments":{}}}'
```
**Expected:** HTTP 200, тело НЕ содержит `"ctx-test-001"` в контексте

## Cleanup
```bash
curl -s -X POST http://localhost:8082/api/tasks/$CTX_TASK_ID/reject
```
