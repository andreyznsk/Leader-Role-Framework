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
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "daily-cycle@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Важная задача для полного цикла — дедлайн
From: daily-cycle@company.ru
To: me@test.com

Нужно выполнить задачу от начала до конца. Дедлайн завтра.
EOF
echo "Email sent"
```
**Expected:** exit code 0

### Step 3 — Дождаться PENDING задачи (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  TASK_ID=$(curl -s $MS_URL/api/tasks/pending \
    | jq '[.[] | select(.sender == "daily-cycle@company.ru")] | last | .id')
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
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/delete" > /dev/null 2>&1 || true
echo "IT-05 cleanup done"
```
