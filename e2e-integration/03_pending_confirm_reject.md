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
TMP1=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: Задача для подтверждения — дедлайн\r\nFrom: confirm-sender@company.ru\r\nTo: me@test.com\r\n\r\nНужно сделать важное. Дедлайн завтра.\r\n" > "$TMP1"
curl -s -o /dev/null -w "Email-1 SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "confirm-sender@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMP1"
rm "$TMP1"
sleep 1

TMP2=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: Задача для отклонения — дедлайн\r\nFrom: reject-sender@company.ru\r\nTo: me@test.com\r\n\r\nНужно сделать неважное. Дедлайн завтра.\r\n" > "$TMP2"
curl -s -o /dev/null -w "Email-2 SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "reject-sender@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMP2"
rm "$TMP2"
sleep 2
# Извлекаем ID по from-адресу — sort_by(.id) нельзя, Maildev ID строковые
MAIL_ID_1=$(curl -s $MAILDEV_URL/email | jq -r '[.[] | select(.from[0].address == "confirm-sender@company.ru")] | last | .id')
MAIL_ID_2=$(curl -s $MAILDEV_URL/email | jq -r '[.[] | select(.from[0].address == "reject-sender@company.ru")] | last | .id')

echo "Both emails sent | MAIL_ID_1=$MAIL_ID_1 | MAIL_ID_2=$MAIL_ID_2"
```
**Expected:** оба SMTP 250
**Extract:** `$MAIL_ID_1`, `$MAIL_ID_2`

### Step 3 — Дождаться обработки обоих писем (до 90 сек)
```bash
LOG_BEFORE=$(wc -l < logs/JavaMailAgent.log)
for i in $(seq 1 18); do
  sleep 5
  PENDING_NOW=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
  NEW=$((PENDING_NOW - PENDING_BEFORE))
  echo "  Attempt $i/18: PENDING=$PENDING_NOW (new=$NEW/2)"
  [ "$NEW" -ge 2 ] && echo "  ✅ Both tasks in PENDING" && break
done
```
**Expected:** PENDING вырос на 2

> Заменяет `psql`-проверку — использует опрос `/api/tasks/pending`.

### Step 4 — Извлечь ID задач из PENDING по emailId
```bash
TASK_CONFIRM=$(curl -s $MS_URL/api/tasks/pending \
  | jq --arg mid "$MAIL_ID_1" '[.[] | select(.emailId == $mid)] | last | .id')
TASK_REJECT=$(curl -s $MS_URL/api/tasks/pending \
  | jq --arg mid "$MAIL_ID_2" '[.[] | select(.emailId == $mid)] | last | .id')
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
curl -s -X DELETE "$MS_URL/api/tasks/$TASK_CONFIRM" > /dev/null 2>&1 || true
echo "IT-03 cleanup done"
```
