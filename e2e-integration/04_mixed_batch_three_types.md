# Scenario: Три письма за один цикл — NOISE / REQUEST / DRAFT

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** HIGH
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
Три письма с разными маркерами за один poll цикл.
MockClaudeRunner логика:
- BUILD + passed + Duration → NOISE → markAsRead
- ДЕДЛАЙН → REQUEST HIGH
- ОТВЕТН → DRAFT

Каждый тип обрабатывается правильно и не мешает другим.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Очистить окружение
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
echo "PENDING before: $PENDING_BEFORE"
```
**Extract:** `$PENDING_BEFORE`

### Step 2 — Письмо 1: CI (BUILD + passed + Duration → NOISE)
```bash
TMP1=$(mktemp)
cat > "$TMP1" <<'EOF'
Subject: Build #500 passed
From: ci@jenkins.local
To: me@test.com

Build #500 completed successfully. Duration: 1m 12s.
EOF
curl -s -o /dev/null -w "Email-1 SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "ci@jenkins.local" --mail-rcpt "me@test.com" --upload-file "$TMP1"
rm "$TMP1"
echo "Email 1 sent: CI → NOISE"
```
**Expected:** SMTP 250

### Step 3 — Письмо 2: задача с дедлайном (→ REQUEST HIGH)
```bash
TMP2=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: Нужен отчёт — дедлайн пятница\r\nFrom: it04-manager@company.ru\r\nTo: me@test.com\r\n\r\nПривет, подготовь квартальный отчёт. Дедлайн — пятница.\r\n" > "$TMP2"
curl -s -o /dev/null -w "Email-2 SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "it04-manager@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMP2"
rm "$TMP2"
echo "Email 2 sent: task → REQUEST"
```
**Expected:** SMTP 250

### Step 4 — Письмо 3: просьба написать ответ (ЧЕРНОВИК → DRAFT)
```bash
TMP3=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: Re: Коммерческое предложение\r\nFrom: it04-partner@external.com\r\nTo: me@test.com\r\n\r\nНам нужно ответное письмо с подтверждением условий.\r\nПодготовь черновик ответного письма пожалуйста.\r\n" > "$TMP3"
curl -s -o /dev/null -w "Email-3 SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "it04-partner@external.com" --mail-rcpt "me@test.com" --upload-file "$TMP3"
rm "$TMP3"

sleep 2
MAIL_ID_MANAGER=$(curl -s $MAILDEV_URL/email \
  | jq -r '[.[] | select(.from[0].address == "it04-manager@company.ru")] | last | .id')
echo "Email 3 sent: reply → DRAFT | MAIL_ID_MANAGER=$MAIL_ID_MANAGER"
```
**Expected:** SMTP 250
**Extract:** `$MAIL_ID_MANAGER`

### Step 5 — Дождаться обработки всех трёх (до 90 сек)
```bash
LOG_BEFORE=$(wc -l < logs/JavaMailAgent.log)
for i in $(seq 1 18); do
  sleep 5
  PENDING_NOW=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
  NEW_PENDING=$((PENDING_NOW - PENDING_BEFORE))
  NEW_LOG=$(tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log | grep -cE "NOISE|REQUEST|DRAFT" 2>/dev/null || echo 0)
  echo "  Attempt $i/18: new_pending=$NEW_PENDING, new_log_hits=$NEW_LOG"
  [ "$NEW_PENDING" -ge 1 ] && [ "$NEW_LOG" -ge 3 ] && echo "  ✅ All 3 processed" && break
done
```
**Expected:** PENDING вырос на 1, в логе — 3+ строки NOISE/REQUEST/DRAFT

> Заменяет `psql`-проверку.

### Step 6 — Классификации подтверждены в логе
```bash
tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log \
  | grep -E "Classified as|Poll finished" | tail -10
```
**Expected:** строки `Classified as NOISE`, `Classified as REQUEST`, `Classified as DRAFT`

### Step 7 — NOISE помечено прочитанным, REQUEST и DRAFT — нет (лог-верификация)
```bash
tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log \
  | grep -E "marked as read|DRAFT|REQUEST" | tail -10
```
**Expected:** `marked as read (NOISE)` присутствует, REQUEST и DRAFT — нет `marked as read`

> Maildev REST `.read` не обновляется при IMAP `\Seen` — проверка через лог.

### Step 8 — Только REQUEST создал PENDING задачу
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
NEW=$((PENDING_AFTER - PENDING_BEFORE))
echo "New PENDING tasks: $NEW (expected: 1)"
```
**Expected:** `$NEW = 1`

### Step 9 — PENDING задача от it04-manager (по emailId)
```bash
TASK_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq --arg mid "$MAIL_ID_MANAGER" '[.[] | select(.emailId == $mid)] | last | .id')
echo "REQUEST task ID: $TASK_ID"
```
**Expected:** числовой ID
**Extract:** `$TASK_ID`

## Cleanup
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/reject" > /dev/null 2>&1 || true
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
echo "IT-04 cleanup done"
```
