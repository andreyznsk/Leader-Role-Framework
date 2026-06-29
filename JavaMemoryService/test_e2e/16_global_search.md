# Scenario: Global Search (CR-MEM-009)

**service:** JavaMemoryService  
**port:** 8082  
**priority:** HIGH  
**depends_on:** postgres

## Steps

### Step 1 — Create a task with vacation keyword
```bash
curl -s -X POST "$MS_URL/api/tasks" \
  -H "Content-Type: application/json" \
  -d '{"title":"E2E отпуск команды","description":"Проверить график отпусков перед спринтом","status":"TODO","priority":"NORMAL"}'
```
**Expected:** HTTP 200 or 201, task created with title containing "отпуск"

### Step 2 — Search QUICK mode — single layer TASK
```bash
curl -s -X POST "$MS_URL/api/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"отпуск","layers":["TASK"],"mode":"QUICK","limit":10}'
```
**Expected:** HTTP 200, `results` array contains item with `layer = "TASK"`, `title` contains "отпуск", `score > 0`

### Step 3 — Search QUICK mode — all available layers
```bash
curl -s -X POST "$MS_URL/api/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"отпуск","layers":["NOTICE","TASK","PEOPLE","RISK","INCIDENT","KNOWLEDGE"],"mode":"QUICK","limit":20}'
```
**Expected:** HTTP 200, `results` is array (may be empty for some layers), no 5xx errors

### Step 4 — Empty query returns 400
```bash
curl -s -o /dev/null -w "%{http_code}" -X POST "$MS_URL/api/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"","layers":["TASK"],"mode":"QUICK"}'
```
**Expected:** HTTP 400

### Step 5 — GET /api/search/layers returns layer registry
```bash
curl -s "$MS_URL/api/search/layers"
```
**Expected:** HTTP 200, JSON array with objects containing `name`, `title`, `enabled`, `available`. NOTICE/TASK/PEOPLE/RISK/INCIDENT/KNOWLEDGE have `available: true`. MAIL/CALENDAR have `available: false`.

### Step 6 — UI smoke test
```bash
curl -s -o /dev/null -w "%{http_code}" "$MS_URL/ui/search"
```
**Expected:** HTTP 200

### Step 7 — UI smoke test with query
```bash
curl -s -G --data-urlencode "q=отпуск" --data-urlencode "mode=QUICK" --data-urlencode "preset=everything" "$MS_URL/ui/search"
```
**Expected:** HTTP 200, body contains "Search LeaderOS" and "отпуск"

### Step 8 — Unavailable layer (MAIL) is ignored, not 500
```bash
curl -s -X POST "$MS_URL/api/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"test","layers":["MAIL"],"mode":"QUICK","limit":5}'
```
**Expected:** HTTP 200, `results` is empty array (MAIL layer filtered out — not available)
