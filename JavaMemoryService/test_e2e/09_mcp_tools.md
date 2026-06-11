# Scenario: MCP Server — tools list (SSE protocol)

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 2.0 (исправлен CR-MEM-SCENARIO-001 — SSE flow)

## Описание
MCP WebMvcSse transport требует двухэтапный flow:
1. Открыть SSE-соединение → получить sessionId
2. Отправить JSON-RPC запросы с sessionId → читать ответы из SSE потока

## Steps

### Step 1 — Открыть SSE-соединение и получить sessionId
```bash
curl -sN --max-time 30 http://localhost:8082/mcp/sse > /tmp/mcp_sse.txt 2>&1 &
SSE_PID=$!
sleep 2
SESSION_ID=$(grep -o 'sessionId=[a-zA-Z0-9_-]*' /tmp/mcp_sse.txt | head -1 | cut -d= -f2)
echo "Session ID: $SESSION_ID"
```
**Expected:** `$SESSION_ID` не пустой

### Step 2 — Initialize MCP сессию
```bash
curl -s -X POST "http://localhost:8082/mcp/message?sessionId=$SESSION_ID" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"e2e-test","version":"1.0"}}}'
sleep 1
```
**Expected:** HTTP 200

### Step 3 — tools/list и проверка обязательных инструментов
```bash
curl -s -X POST "http://localhost:8082/mcp/message?sessionId=$SESSION_ID" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
sleep 1

TOOLS_JSON=$(cat /tmp/mcp_sse.txt | grep -o '"name":"[a-zA-Z]*"' | sort -u)
for TOOL in getContext getTasks createTask markTaskDone createIncident addRisk addPeopleNote; do
  echo "$TOOLS_JSON" | grep -q "\"$TOOL\"" \
    && echo "✅ $TOOL" || echo "❌ $TOOL MISSING"
done
```
**Expected:** все 7 инструментов показывают ✅

### Step 4 — Вызвать getTasks без ошибок
```bash
TODAY=$(date +%Y-%m-%d)
curl -s -X POST "http://localhost:8082/mcp/message?sessionId=$SESSION_ID" \
  -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"getTasks\",\"arguments\":{\"date\":\"$TODAY\"}}}"
sleep 1
cat /tmp/mcp_sse.txt | grep '"error"' | tail -3 || echo "No errors — OK"
```
**Expected:** нет блоков `"error"` для id=3

## Cleanup
```bash
kill $SSE_PID 2>/dev/null || true
rm -f /tmp/mcp_sse.txt
```
