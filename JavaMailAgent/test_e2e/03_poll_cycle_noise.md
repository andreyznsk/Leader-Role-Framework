# Scenario: Poll Cycle — NOISE письмо обрабатывается и помечается прочитанным

**service:** JavaMailAgent
**port:** 8080
**priority:** HIGH
**depends_on:** postgres, maildev
**profile:** local (mock.agent=true)

## Описание
MockClaudeRunner → NOISE если subject/body содержит BUILD/PASSED/SUCCESS/DURATION.
Отправить CI-уведомление → дождаться poll цикла →
письмо помечено прочитанным (markAsRead только для NOISE) →
запись в processed_emails с agent_type=NOISE.

## Steps

### Step 1 — Очистить Maildev
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
echo "Maildev cleared"
```
**Expected:** команда выполнена без ошибки

### Step 2 — Отправить NOISE письмо (BUILD/PASSED → MockClaudeRunner → NOISE)
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "ci@jenkins.local" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Build #321 passed
From: ci@jenkins.local
To: me@test.com

Build #321 completed successfully. Duration: 2m 34s. All tests passed.
EOF
echo "NOISE email sent (contains: Build, passed, Duration)"
```
**Expected:** exit code 0

### Step 3 — Письмо в Maildev как unread
```bash
sleep 1
curl -s http://localhost:1080/email | jq '[.[] | select(.read == false)] | length'
```
**Expected:** >= 1

### Step 4 — Дождаться poll цикла (до 90 сек) — письмо помечено прочитанным
```bash
echo "Waiting for poll cycle (NOISE → markAsRead)..."
for i in $(seq 1 18); do
  sleep 5
  UNREAD=$(curl -s http://localhost:1080/email | jq '[.[] | select(.read == false)] | length')
  echo "  Attempt $i/18: unread=$UNREAD"
  if [ "$UNREAD" -eq 0 ]; then
    echo "  ✅ Letter marked as read — NOISE processed"
    break
  fi
done
```
**Expected:** unread = 0 в течение 90 секунд

### Step 5 — Письмо помечено прочитанным
```bash
curl -s http://localhost:1080/email | jq '[.[] | select(.read == false)] | length'
```
**Expected:** `0`

### Step 6 — Запись в processed_emails с agent_type=NOISE
```bash
PGPASSWORD=mailagent_password psql \
  -h localhost -U mailagent_user -d leader_framework \
  -c "SELECT email_id, sender, subject, agent_type FROM mailagent.processed_emails WHERE sender='ci@jenkins.local' ORDER BY processed_at DESC LIMIT 3;"
```
**Expected:** строка с `sender=ci@jenkins.local`, `agent_type=NOISE`

### Step 7 — Лог содержит NOISE классификацию
```bash
grep -i "NOISE\|classified as NOISE\|poll finished" logs/JavaMailAgent.log | tail -5
```
**Expected:** строка с `NOISE`

## Cleanup
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
echo "Cleanup done"
```
