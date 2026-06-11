# Scenario: Health Check

**service:** JavaMailAgent
**port:** 8080
**priority:** CRITICAL
**depends_on:** maildev

## Steps

### Step 1 — Actuator health
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health
```
**Expected:** HTTP 200

### Step 2 — UI status page доступна
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ui/status
```
**Expected:** HTTP 200

## Cleanup
# ничего не требуется
