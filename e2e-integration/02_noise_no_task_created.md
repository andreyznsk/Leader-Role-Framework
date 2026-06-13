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
TMPFILE=$(mktemp)
cat > "$TMPFILE" <<'EOF'
Subject: Build #999 passed
From: ci@jenkins.local
To: me@test.com

Build #999 completed successfully. Duration: 2m 34s. All tests passed.
EOF

curl -s -o /dev/null -w "SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "ci@jenkins.local" \
  --mail-rcpt "me@test.com" \
  --upload-file "$TMPFILE"
rm "$TMPFILE"

sleep 2
MAIL_ID=$(curl -s $MAILDEV_URL/email | jq -r 'sort_by(.id) | last | .id')
echo "NOISE email sent | Maildev id=$MAIL_ID"
```
**Expected:** SMTP 250, Maildev count >= 1
**Extract:** `$MAIL_ID` — ID письма в Maildev

### Step 3 — Дождаться poll и обработки NOISE (до 90 сек)
```bash
LOG_BEFORE=$(wc -l < logs/JavaMailAgent.log)
for i in $(seq 1 18); do
  sleep 5
  NEW_NOISE=$(tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log | grep -c "NOISE" 2>/dev/null || echo 0)
  echo "  Attempt $i/18: new NOISE log lines=$NEW_NOISE"
  [ "$NEW_NOISE" -ge 1 ] && echo "  ✅ NOISE classified in log" && break
done
```
**Expected:** хотя бы 1 новая строка с "NOISE" в логе после отправки

> ⚠️ Maildev REST API поле `read` не обновляется при IMAP `\Seen` от внешнего клиента.
> Верификация через лог MailAgent — надёжнее.

### Step 4 — Задача НЕ создана, письмо классифицировано как NOISE
```bash
NOISE_LINE=$(tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log \
  | grep -E "Classified as NOISE|marked as read .NOISE.")
echo "NOISE evidence in log:"
echo "$NOISE_LINE"
```
**Expected:** строки `Classified as NOISE` и `marked as read (NOISE)` присутствуют

> Заменяет `psql`-проверку — `psql`-client может не быть установлен на хосте.

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
