# Scenario: MCP Tools List

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — MCP tools/list handshake
```bash
curl -s -X POST http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```
**Expected:** HTTP 200, тело содержит все обязательные tools:
- `getContext`
- `getTasks`
- `createTask`
- `markTaskDone`
- `createIncident`
- `addRisk`
- `addPeopleNote`

## Cleanup
# ничего не требуется
