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
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "ci@jenkins.local" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Build #500 passed
From: ci@jenkins.local
To: me@test.com

Build #500 completed successfully. Duration: 1m 12s.
EOF
echo "Email 1 sent: CI → NOISE"
```
**Expected:** exit code 0

### Step 3 — Письмо 2: задача с дедлайном (→ REQUEST HIGH)
```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "it04-manager@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Нужен отчёт — дедлайн пятница
From: it04-manager@company.ru
To: me@test.com

Привет, подготовь квартальный отчёт. Дедлайн — пятница.
EOF
echo "Email 2 sent: task → REQUEST HIGH"
```
**Expected:** exit code 0

### Step 4 — Письмо 3: просьба написать ответ (ОТВЕТН → DRAFT)
```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "it04-partner@external.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Re: Коммерческое предложение
From: it04-partner@external.com
To: me@test.com

Нам нужно ответное письмо с подтверждением условий.
Подготовь черновик ответного письма пожалуйста.
EOF
echo "Email 3 sent: reply → DRAFT"
```
**Expected:** exit code 0

### Step 5 — Дождаться обработки всех трёх (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender IN ('ci@jenkins.local','it04-manager@company.ru','it04-partner@external.com');" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: processed=$COUNT/3"
  [ "$COUNT" -ge 3 ] 2>/dev/null && echo "  ✅ All 3 processed" && break
done
```
**Expected:** count = 3

### Step 6 — Типы в processed_emails соответствуют ожиданиям
```bash
psql -h $PGHOST -U $PGUSER -d $PGDATABASE \
  -c "SELECT sender, agent_type FROM mailagent.processed_emails WHERE sender IN ('ci@jenkins.local','it04-manager@company.ru','it04-partner@external.com') ORDER BY processed_at DESC;"
```
**Expected:**
- `ci@jenkins.local` → `NOISE`
- `it04-manager@company.ru` → `REQUEST`
- `it04-partner@external.com` → `DRAFT`

### Step 7 — NOISE помечено прочитанным, REQUEST и DRAFT — нет
```bash
curl -s $MAILDEV_URL/email | jq '[.[] | {from: .from[0].address, read}]'
```
**Expected:** `ci@jenkins.local read=true`, остальные `read=false`

### Step 8 — Только REQUEST создал PENDING задачу
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
NEW=$((PENDING_AFTER - PENDING_BEFORE))
echo "New PENDING tasks: $NEW (expected: 1)"
```
**Expected:** `$NEW = 1`

### Step 9 — PENDING задача от it04-manager
```bash
TASK_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq '[.[] | select(.sender == "it04-manager@company.ru")] | last | .id')
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
