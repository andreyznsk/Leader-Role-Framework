# Scenario: Health Check

**service:** JavaMailAgent
**port:** 8080
**priority:** CRITICAL
**depends_on:** postgres, maildev

## Preconditions
- JavaMailAgent запущен с профилем `local` на :8080
- Maildev запущен (адрес из переменной MAILDEV_URL)

## Переменные окружения
```bash
# Установить перед запуском сценария (по умолчанию — стандартный порт)
export MAILDEV_URL="${MAILDEV_URL:-http://localhost:1080}"
export MAILDEV_SMTP="${MAILDEV_SMTP:-localhost:1025}"
echo "Maildev URL: $MAILDEV_URL"
echo "Maildev SMTP: $MAILDEV_SMTP"
```

## Steps

### Step 1 — Actuator health HTTP 200
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health
```
**Expected:** HTTP 200

### Step 2 — Статус UP
```bash
curl -s http://localhost:8080/actuator/health
```
**Expected:** тело содержит `"status":"UP"`

### Step 3 — Maildev UI доступен
```bash
curl -s -o /dev/null -w "%{http_code}" "$MAILDEV_URL"
```
**Expected:** HTTP 200

### Step 4 — Maildev API возвращает JSON-массив
```bash
curl -s "$MAILDEV_URL/email"
```
**Expected:** HTTP 200, тело начинается с `[`

### Step 5 — UI /ui/status доступен
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ui/status
```
**Expected:** HTTP 200

### Step 6 — Лог содержит успешное подключение к Maildev
```bash
grep -i "maildev connection\|Maildev.*OK\|connection OK" logs/JavaMailAgent.log | tail -3
```
**Expected:** строка содержит `OK` без `FAILED`

## Cleanup
# ничего не требуется
