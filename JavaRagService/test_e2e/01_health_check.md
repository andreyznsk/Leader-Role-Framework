# Scenario: Health Check

**service:** JavaRagService
**port:** 8081
**priority:** CRITICAL
**depends_on:** opensearch, postgres

## Steps

### Step 1 — RAG status endpoint
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/mcp/rag_status
```
**Expected:** HTTP 200

### Step 2 — OpenSearch доступен
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:9200
```
**Expected:** HTTP 200

## Cleanup
# ничего не требуется
