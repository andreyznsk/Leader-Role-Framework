# Scenario: NOISE письмо — markAsRead, задача НЕ создаётся

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** CRITICAL
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
CI-уведомление должно быть поглощено без создания задачи.
MockClaudeRunner: BUILD + passed + Duration → NOISE → markAsRead.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Очистить окружение и запомнить baseline
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
echo "PENDING before: $PENDING_BEFORE"
```
**Extract:** `$PENDING_BEFORE`

### Step 2 — Отправить CI уведомление (BUILD + passed + Duration → NOISE)
```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "ci@jenkins.local" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Build #999 passed
From: ci@jenkins.local
To: me@test.com

Build #999 completed successfully. Duration: 2m 34s. All tests passed.
EOF
echo "NOISE email sent"
```
**Expected:** exit code 0

### Step 3 — Дождаться poll и markAsRead (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  UNREAD=$(curl -s $MAILDEV_URL/email | jq '[.[] | select(.read == false)] | length')
  echo "  Attempt $i/18: unread=$UNREAD"
  [ "$UNREAD" -eq 0 ] && echo "  ✅ Marked as read" && break
done
```
**Expected:** unread = 0

### Step 4 — Запись в processed_emails с agent_type=NOISE
```bash
psql -h $PGHOST -U $PGUSER -d $PGDATABASE \
  -c "SELECT sender, agent_type FROM mailagent.processed_emails WHERE sender='ci@jenkins.local' ORDER BY processed_at DESC LIMIT 1;"
```
**Expected:** `agent_type=NOISE`

### Step 5 — Задача НЕ попала в PENDING
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
echo "PENDING before=$PENDING_BEFORE, after=$PENDING_AFTER"
[ "$PENDING_AFTER" = "$PENDING_BEFORE" ] && echo "✅ No new PENDING tasks" || echo "❌ Unexpected PENDING task!"
```
**Expected:** `$PENDING_AFTER == $PENDING_BEFORE`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
echo "IT-02 cleanup done"
```
