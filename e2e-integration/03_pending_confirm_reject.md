# Scenario: PENDING → confirm → TODO / reject → DELETED

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** CRITICAL
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
Два письма: первое подтверждается (PENDING → TODO), второе отклоняется (PENDING → DELETED).
Отклонённая задача не появляется в плане дня.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Очистить окружение
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
TODAY=$(date +%Y-%m-%d)
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
echo "PENDING before: $PENDING_BEFORE"
```
**Extract:** `$TODAY`, `$PENDING_BEFORE`

### Step 2 — Отправить два REQUEST письма
```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "confirm-sender@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Задача для подтверждения — дедлайн
From: confirm-sender@company.ru
To: me@test.com

Нужно сделать важное. Дедлайн завтра.
EOF

curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "reject-sender@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Задача для отклонения — дедлайн
From: reject-sender@company.ru
To: me@test.com

Нужно сделать неважное. Дедлайн завтра.
EOF
echo "Both emails sent"
```
**Expected:** exit code 0

### Step 3 — Дождаться обработки обоих писем (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender IN ('confirm-sender@company.ru','reject-sender@company.ru');" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: processed=$COUNT/2"
  [ "$COUNT" -ge 2 ] 2>/dev/null && echo "  ✅ Both processed" && break
done
```
**Expected:** count = 2

### Step 4 — Извлечь ID задач из PENDING
```bash
TASK_CONFIRM=$(curl -s $MS_URL/api/tasks/pending \
  | jq '[.[] | select(.sender == "confirm-sender@company.ru")] | last | .id')
TASK_REJECT=$(curl -s $MS_URL/api/tasks/pending \
  | jq '[.[] | select(.sender == "reject-sender@company.ru")] | last | .id')
echo "Confirm task: $TASK_CONFIRM | Reject task: $TASK_REJECT"
```
**Expected:** оба числовые ID
**Extract:** `$TASK_CONFIRM`, `$TASK_REJECT`

### Step 5 — Подтвердить первую: PENDING → TODO
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_CONFIRM/confirm" | jq '{id, status}'
```
**Expected:** `"status":"TODO"`

### Step 6 — Отклонить вторую: PENDING → DELETED
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_REJECT/reject" | jq '{id, status}'
```
**Expected:** `"status":"DELETED"`

### Step 7 — Подтверждённая задача в плане дня, отклонённая — нет
```bash
CONFIRM_IN_PLAN=$(curl -s "$MS_URL/api/tasks?date=$TODAY" \
  | jq "[.[] | select(.id == $TASK_CONFIRM)] | length")
REJECT_IN_PLAN=$(curl -s "$MS_URL/api/tasks?date=$TODAY" \
  | jq "[.[] | select(.id == $TASK_REJECT)] | length")
echo "Confirmed in plan: $CONFIRM_IN_PLAN (expected 1)"
echo "Rejected in plan: $REJECT_IN_PLAN (expected 0)"
```
**Expected:** `$CONFIRM_IN_PLAN = 1`, `$REJECT_IN_PLAN = 0`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
curl -s -X POST "$MS_URL/api/tasks/$TASK_CONFIRM/delete" > /dev/null 2>&1 || true
echo "IT-03 cleanup done"
```
