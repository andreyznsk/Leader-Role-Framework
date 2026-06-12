# Scenario: Maildev — отправить письмо и прочитать через API

**service:** JavaMailAgent
**port:** 8080
**priority:** HIGH
**depends_on:** maildev

## Описание
Проверить что Maildev принимает письма через SMTP и отдаёт через HTTP API.
Базовый тест инфраструктуры — без агента.

Особенности Maildev:
- PATCH /email/:id/read НЕ реализован — письмо становится read=true автоматически при GET по id
- SMTP требует MIME-заголовки для корректной передачи кириллицы

## Переменные окружения
```bash
export MAILDEV_URL="${MAILDEV_URL:-http://localhost:1080}"
export MAILDEV_SMTP="${MAILDEV_SMTP:-localhost:1025}"
echo "Maildev: $MAILDEV_URL (SMTP: $MAILDEV_SMTP)"
```

## Steps

### Step 1 — Очистить ящик перед тестом
```bash
curl -s -X DELETE "$MAILDEV_URL/email/all"
echo "Maildev inbox cleared"
```
**Expected:** команда выполнена без ошибки (возвращает `true`)

### Step 2 — Отправить письмо через SMTP (с MIME-заголовками для UTF-8)
```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "sender@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
MIME-Version: 1.0
Content-Type: text/plain; charset=UTF-8
Content-Transfer-Encoding: 8bit
Subject: E2E Test: simple letter
From: sender@test.com
To: me@test.com

This is an automated E2E test message for Maildev.
EOF
echo "Exit code: $?"
```
**Expected:** exit code 0

### Step 3 — Письмо появилось в Maildev
```bash
sleep 1
EMAILS=$(curl -s "$MAILDEV_URL/email")
echo "$EMAILS" | jq 'length'
EMAIL_ID=$(echo "$EMAILS" | jq -r '.[0].id')
echo "$EMAILS" | jq '.[0] | {id, subject, from: .from[0].address, read}'
```
**Expected:** length >= 1, subject содержит `E2E Test`, `"read":false`
**Extract:** `id` → `$EMAIL_ID`

### Step 4 — Прочитать письмо по ID (GET помечает как read=true автоматически)
```bash
curl -s "$MAILDEV_URL/email/$EMAIL_ID" | jq '{subject, read}'
```
**Expected:** HTTP 200, `subject` содержит `E2E Test`

### Step 5 — Письмо теперь read=true (автоматически после GET)
```bash
curl -s "$MAILDEV_URL/email/$EMAIL_ID" | jq '.read'
```
**Expected:** `true`

### Step 6 — Unread фильтр: в ящике нет непрочитанных
```bash
curl -s "$MAILDEV_URL/email" | jq '[.[] | select(.read == false)] | length'
```
**Expected:** `0`

## Cleanup
```bash
curl -s -X DELETE "$MAILDEV_URL/email/all" > /dev/null
echo "Cleanup: Maildev inbox cleared"
```
