# Scenario: Poll Cycle — REQUEST письмо → plans/today.md + processed_emails

**service:** JavaMailAgent
**port:** 8080
**priority:** HIGH
**depends_on:** postgres, maildev
**profile:** local (mock.agent=true, memory.service.enabled=false)

## Описание
MockClaudeRunner → REQUEST если нет BUILD/PASSED/ОТВЕТН/ЧЕРНОВИК (default).
"ДЕДЛАЙН" в теме → priority=HIGH.
Проверить: запись в processed_emails, строка в plans/today.md,
письмо НЕ помечено прочитанным (REQUEST остаётся unread).

## Steps

### Step 1 — Очистить окружение
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
PLAN_LINES_BEFORE=$(wc -l < plans/today.md 2>/dev/null || echo "0")
echo "today.md lines before: $PLAN_LINES_BEFORE"
```
**Extract:** `$PLAN_LINES_BEFORE`

### Step 2 — Отправить REQUEST письмо (нет BUILD/PASSED → REQUEST, ДЕДЛАЙН → HIGH)
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "ivanov@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Нужен ревью PR #42 — дедлайн сегодня
From: ivanov@company.ru
To: me@test.com

Привет, можешь посмотреть PR #42?
Там важные изменения, дедлайн сегодня.
EOF
echo "REQUEST email sent (no BUILD/PASSED, contains ДЕДЛАЙН → HIGH)"
```
**Expected:** exit code 0

### Step 3 — Дождаться обработки (до 90 сек)
```bash
echo "Waiting for REQUEST processing..."
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(PGPASSWORD=mailagent_password psql \
    -h localhost -U mailagent_user -d leader_framework -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender='ivanov@company.ru' AND agent_type='REQUEST';" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: REQUEST count=$COUNT"
  if [ "$COUNT" -ge 1 ] 2>/dev/null; then
    echo "  ✅ REQUEST processed"
    break
  fi
done
```
**Expected:** count >= 1

### Step 4 — Запись в processed_emails с agent_type=REQUEST
```bash
PGPASSWORD=mailagent_password psql \
  -h localhost -U mailagent_user -d leader_framework \
  -c "SELECT email_id, sender, subject, agent_type FROM mailagent.processed_emails WHERE sender='ivanov@company.ru' ORDER BY processed_at DESC LIMIT 3;"
```
**Expected:** строка с `agent_type=REQUEST`

### Step 5 — Строка добавлена в plans/today.md (содержит [ ] и HIGH/P1)
```bash
tail -10 plans/today.md
```
**Expected:** последние строки содержат `[ ]` и `P1` (HIGH → P1 в MockClaudeRunner)

### Step 6 — Письмо НЕ помечено прочитанным (REQUEST остаётся unread)
```bash
curl -s http://localhost:1080/email \
  | jq '[.[] | select(.from[0].address == "ivanov@company.ru" and .read == false)] | length'
```
**Expected:** `1` — письмо от ivanov остаётся непрочитанным

### Step 7 — Файл письма появился в mail/processed/
```bash
ls -la mail/processed/ 2>/dev/null | grep -v "^total\|^d" | tail -5
```
**Expected:** файл с именем содержащим emailId

## Cleanup
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
echo "Cleanup done"
```
