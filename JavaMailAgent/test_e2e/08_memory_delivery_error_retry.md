# Scenario: Memory delivery failure -> checkpoint retry without duplicate side-effects

**service:** JavaMailAgent
**port:** 8080
**priority:** HIGH
**depends_on:** postgres, maildev
**profile:** local

## Description

Проверить, что при падении JavaMemoryService письмо типа `REQUEST` переходит в
`mailagent.processed_emails.status=ERROR`, сохраняет checkpoint
`failed_route=MEMORY_PENDING_TASK`, не дублирует строку в `plans/today.md`, а при
следующем poll сначала ретраит `ERROR` очередь и только потом берёт новые письма.

## Steps

### Step 1 - Clear mailbox and remember current plan count
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
# local profile: MockAgentClient writes "Mock task from email <emailId>", not the email subject
PLAN_MATCHES_BEFORE=$(grep -c "Mock task from email" plans/today.md 2>/dev/null || echo "0")
echo "plan matches before: $PLAN_MATCHES_BEFORE"
```

### Step 2 - Stop JavaMemoryService
```bash
pkill -f "memory-service" || true
sleep 2
curl -sf http://localhost:8082/actuator/health && exit 1 || echo "memory-service is down"
```

### Step 3 - Send REQUEST email
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "retry@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Retry memory delivery task
From: retry@test.com
To: me@test.com

Please review the retry flow change.
EOF
```

### Step 4 - Wait for first poll and verify ERROR checkpoint
```bash
for i in $(seq 1 18); do
  sleep 5
  ROW=$(PGPASSWORD=mailagent_password psql \
    -h localhost -U mailagent_user -d leader_framework -t -A \
    -c "SELECT status || '|' || response_type || '|' || failed_route || '|' || attempts_count FROM mailagent.processed_emails WHERE sender='retry@test.com' ORDER BY updated_at DESC LIMIT 1;")
  echo "attempt $i: $ROW"
  [ -n "$ROW" ] && break
done
```
**Expected:** `ERROR|REQUEST|MEMORY_PENDING_TASK|1`

### Step 5 - Verify plan line was appended only once
```bash
# local profile: MockAgentClient writes "Mock task from email <emailId>", not the email subject
PLAN_MATCHES_AFTER=$(grep -c "Mock task from email" plans/today.md 2>/dev/null || echo "0")
echo "plan matches after: $PLAN_MATCHES_AFTER"
echo "new lines added: $((PLAN_MATCHES_AFTER - PLAN_MATCHES_BEFORE))"
[ "$((PLAN_MATCHES_AFTER - PLAN_MATCHES_BEFORE))" = "1" ] && echo "PASS" || echo "FAIL"
```
**Expected:** exactly 1 new `Mock task from email` line added since Step 1

### Step 6 - Send second unread email
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "second@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Second email after error
From: second@test.com
To: me@test.com

This should wait behind the retry queue.
EOF
```

### Step 7 - Start JavaMemoryService again
```bash
SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar > /tmp/memory-service-retry.log 2>&1 &
sleep 10
curl -sf http://localhost:8082/actuator/health
```

### Step 8 - Wait for retry poll and verify final state
```bash
for i in $(seq 1 18); do
  sleep 5
  ROW=$(PGPASSWORD=mailagent_password psql \
    -h localhost -U mailagent_user -d leader_framework -t -A \
    -c "SELECT status || '|' || failed_route || '|' || attempts_count FROM mailagent.processed_emails WHERE sender='retry@test.com' ORDER BY updated_at DESC LIMIT 1;")
  echo "attempt $i: $ROW"
  # accept any attempt count — memory-service startup may cause >1 retry polls
  [[ "$ROW" =~ ^PROCESSED\|NONE\|[0-9]+$ ]] && break
done
```
**Expected:** `PROCESSED|NONE|<N>` where N >= 1 (exact count depends on memory-service startup time)

### Step 9 - Verify task created once and plan line still single
```bash
curl -s "http://localhost:8082/api/tasks/pending" | jq '[.[] | select(.emailId != null and .emailId != "")] | length'
# local profile: MockAgentClient writes "Mock task from email <emailId>", not the email subject
PLAN_FINAL=$(grep -c "Mock task from email" plans/today.md 2>/dev/null || echo "0")
echo "final plan matches: $PLAN_FINAL (was $PLAN_MATCHES_BEFORE before test)"
```
**Expected:** pending task for retry email exists, and `Mock task from email` line count increased by exactly 1 vs Step 1

### Step 10 - Verify second email was processed only after retry queue
```bash
PGPASSWORD=mailagent_password psql \
  -h localhost -U mailagent_user -d leader_framework \
  -c "SELECT sender, status, response_type, updated_at FROM mailagent.processed_emails WHERE sender IN ('retry@test.com','second@test.com') ORDER BY updated_at;"
```
**Expected:** запись `retry@test.com` завершается раньше или в том же poll раньше `second@test.com`
