# Scenario: Полный дневной цикл — письмо → PENDING → TODO → IN_PROGRESS → DONE

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** HIGH
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
Полный жизненный цикл задачи от входящего письма до завершения.
Проверяет все промежуточные статусы: PENDING → TODO → IN_PROGRESS → DONE.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Очистить окружение
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
TODAY=$(date +%Y-%m-%d)
echo "TODAY=$TODAY"
```
**Extract:** `$TODAY`

### Step 2 — Отправить REQUEST письмо
```bash
TMPFILE=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: Важная задача для полного цикла — дедлайн\r\nFrom: daily-cycle@company.ru\r\nTo: me@test.com\r\n\r\nНужно выполнить задачу от начала до конца. Дедлайн завтра.\r\n" > "$TMPFILE"
curl -s -o /dev/null -w "SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "daily-cycle@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMPFILE"
rm "$TMPFILE"
sleep 2
MAIL_ID=$(curl -s $MAILDEV_URL/email | jq -r '[.[] | select(.from[0].address == "daily-cycle@company.ru")] | last | .id')
echo "Email sent | MAIL_ID=$MAIL_ID"
```
**Expected:** SMTP 250
**Extract:** `$MAIL_ID`

### Step 3 — Дождаться PENDING задачи (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  TASK_ID=$(curl -s $MS_URL/api/tasks/pending \
    | jq --arg mid "$MAIL_ID" '[.[] | select(.emailId == $mid)] | last | .id')
  [ -n "$TASK_ID" ] && [ "$TASK_ID" != "null" ] && echo "  ✅ PENDING task: $TASK_ID" && break
  echo "  Attempt $i/18: waiting..."
done
echo "TASK_ID=$TASK_ID"
```
**Expected:** числовой ID
**Extract:** `$TASK_ID`

### Step 4 — Статус: PENDING
```bash
curl -s $MS_URL/api/tasks/pending \
  | jq "[.[] | select(.id == $TASK_ID)] | first | {id, status}"
```
**Expected:** `"status":"PENDING"`

### Step 5 — Подтвердить: PENDING → TODO
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/confirm" | jq '{id, status}'
```
**Expected:** `"status":"TODO"`

### Step 6 — Перевести в работу: TODO → IN_PROGRESS
```bash
curl -s -X PATCH "$MS_URL/api/tasks/$TASK_ID/status" \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_PROGRESS"}' | jq '{id, status}'
```
**Expected:** `"status":"IN_PROGRESS"`

### Step 7 — Задача видна в плане дня как IN_PROGRESS
```bash
curl -s "$MS_URL/api/tasks?date=$TODAY" \
  | jq "[.[] | select(.id == $TASK_ID)] | first | {id, status}"
```
**Expected:** `"status":"IN_PROGRESS"`

### Step 8 — Завершить: IN_PROGRESS → DONE
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/done" | jq '{id, status}'
```
**Expected:** `"status":"DONE"`

### Step 9 — Задача DONE видна в плане дня
```bash
curl -s "$MS_URL/api/tasks?date=$TODAY" \
  | jq "[.[] | select(.id == $TASK_ID)] | first | {id, status}"
```
**Expected:** `"status":"DONE"`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
curl -s -X DELETE "$MS_URL/api/tasks/$TASK_ID" > /dev/null 2>&1 || true
echo "IT-05 cleanup done"
```
