# Scenario: Settings Control Plane

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0

## Description
Check centralized plugin settings, masked secret handling, and plugin heartbeat status in MemoryService.

## Preconditions
- JavaMemoryService is running on `:8082`

## Steps

### Step 1 — Open settings UI
```bash
curl -s -o /tmp/memory-settings.html -w "%{http_code}" "http://localhost:8082/ui/settings"
```
**Expected:** HTTP code `200`

### Step 2 — Verify system block and registered plugins
```bash
grep -E "Settings|Active Spring profile|Agent provider|Mail Plugin|Chat Plugin" /tmp/memory-settings.html
```
**Expected:** all labels are present

### Step 3 — Save Mail plugin settings
```bash
curl -s -X PUT "http://localhost:8082/api/settings/plugins/mail" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "config": {
      "protocol": "imap",
      "login": "leader@example.com",
      "password": "plain-secret",
      "host": "imap.example.com",
      "port": 993,
      "ssl": true,
      "pollIntervalSeconds": 60,
      "foldersExclude": ["Spam", "Junk"]
    }
  }'
```
**Expected:** response contains `"enabled":true`, contains `"passwordMasked":"********"`, and does not contain `plain-secret`

### Step 4 — Read saved plugin settings
```bash
curl -s "http://localhost:8082/api/settings/plugins/mail"
curl -s "http://localhost:8082/api/plugins/mail/config"
```
**Expected:** both responses contain masked password state and do not contain `plain-secret`

### Step 5 — Update plugin heartbeat
```bash
curl -s -X POST "http://localhost:8082/api/plugins/mail/heartbeat" \
  -H "Content-Type: application/json" \
  -d '{"status":"UP","message":"poller running"}'
curl -s "http://localhost:8082/api/settings/plugins"
```
**Expected:** Mail plugin status becomes `UP` and `lastHeartbeatAt` is populated

### Step 6 — Trigger connection test
```bash
curl -s -X POST "http://localhost:8082/api/settings/plugins/mail/test-connection"
```
**Expected:** response contains `success` boolean and `message`
