# Scenario: CR-MEM-036 task due-date range REST / MCP

**service:** JavaMemoryService
**port:** 8082 (local) / 8090 (ollama)
**priority:** HIGH
**depends_on:** postgres

## Preconditions
- JavaMemoryService built with CR-MEM-036 and running with local profile, or the user-approved ollama profile.
- Python 3, PostgreSQL; dedicated test environment (creates 1002 tasks).

## Steps

### Step 1 — REST validation, inclusive due dates, MCP discovery/call, regression and performance
```bash
python3 JavaMemoryService/test_e2e/task-date-range.py
```
**Expected:** exit 0, PASS REST (90 days, <2 seconds), PASS validation, PASS MCP tools/list and tools/call and old getTasks. Records seeded count, returned count and measured HTTP duration. `RANGE_TEST_SIZE` defaults to 1000; `MEMORY_BASE_URL` defaults to http://localhost:8082.

## Cleanup
The script's finally block archives every created task through existing DELETE semantics, including on assertion failure. Run only against a dedicated test instance: archived fixtures remain in the database.

### Read-only smoke on an existing ollama instance
```bash
MEMORY_BASE_URL=http://localhost:8090 python3 JavaMemoryService/test_e2e/task-date-range.py --read-only
```
**Expected:** schema from tools/list requires only from/to; MCP results match REST; all six status filters succeed; four invalid requests produce HTTP 400 and MCP isError; getTasks regression succeeds. No fixture writes. Empty results are allowed, so this does not replace the fixture/performance scenario.
