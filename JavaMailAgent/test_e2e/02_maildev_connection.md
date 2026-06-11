# Scenario: Maildev Connection

**service:** JavaMailAgent
**port:** 8080
**priority:** HIGH
**depends_on:** maildev

## Preconditions
- Maildev запущен на :1080
- JavaMailAgent запущен с профилем local

## Steps

### Step 1 — Maildev доступен
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:1080
```
**Expected:** HTTP 200

### Step 2 — Maildev API — список писем пуст или возвращает массив
```bash
curl -s http://localhost:1080/email
```
**Expected:** HTTP 200, тело является JSON-массивом (может быть пустым `[]`)

### Step 3 — Отправить тестовое письмо через SMTP
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "sender@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: E2E Test Mail
From: sender@test.com
To: me@test.com

This is an automated E2E test message.
EOF
```
**Expected:** команда завершается с кодом 0 (без ошибки)

### Step 4 — Письмо появилось в Maildev
```bash
sleep 2
curl -s http://localhost:1080/email
```
**Expected:** HTTP 200, массив содержит хотя бы одно письмо с subject содержащим `E2E Test Mail`

## Cleanup
```bash
# Удалить все тестовые письма
curl -s -X DELETE http://localhost:1080/email/all
```
