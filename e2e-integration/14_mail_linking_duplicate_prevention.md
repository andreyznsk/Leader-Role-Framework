# Scenario: Mail Linking — reply в thread не создаёт дубль задачи

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** CRITICAL
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
Первое письмо создаёт обычную задачу. Второе письмо в том же thread не должно
создать вторую standalone-задачу: вместо этого Mail Agent должен предложить
`LINK_TO_TASK` к уже существующей задаче.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Очистить окружение и снять baseline
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
RUN_ID="it14-$(date +%s)"
TODAY=$(date +%Y-%m-%d)
CURRENT_BEFORE=$(curl -s "$MS_URL/api/tasks?date=$TODAY" | jq 'length')
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')

echo "RUN_ID=$RUN_ID | CURRENT_BEFORE=$CURRENT_BEFORE | PENDING_BEFORE=$PENDING_BEFORE"
```
**Extract:** `$RUN_ID`, `$TODAY`, `$CURRENT_BEFORE`, `$PENDING_BEFORE`

### Step 2 — Отправить первое REQUEST письмо и дождаться PENDING
```bash
TMP1=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: $RUN_ID Release tracking\r\nFrom: duplicate-sender@company.ru\r\nTo: me@test.com\r\n\r\nНужно начать работу по релизу $RUN_ID.\r\n" > "$TMP1"
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "duplicate-sender@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file "$TMP1"
rm "$TMP1"
sleep 2
MAIL_ID_1=$(curl -s $MAILDEV_URL/email | jq -r '[.[] | select(.from[0].address == "duplicate-sender@company.ru")] | last | .id')

for i in $(seq 1 18); do
  sleep 5
  TASK_JSON=$(curl -s $MS_URL/api/tasks/pending \
    | jq --arg mid "$MAIL_ID_1" '[.[] | select(.emailId == $mid)] | last')
  if [ "$TASK_JSON" != "null" ]; then
    echo "$TASK_JSON" | jq '{id, title, status, pendingType}'
    break
  fi
  echo "Attempt $i/18: first pending not found yet"
done
```
**Expected:** найден первый pending task
**Extract:** `$MAIL_ID_1`

### Step 3 — Подтвердить первую задачу и получить targetTaskId
```bash
TASK_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq --arg mid "$MAIL_ID_1" '[.[] | select(.emailId == $mid)] | last | .id')

curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/confirm" | jq '{id, status, title}'
CURRENT_AFTER_FIRST=$(curl -s "$MS_URL/api/tasks?date=$TODAY" | jq 'length')
echo "TASK_ID=$TASK_ID | CURRENT_AFTER_FIRST=$CURRENT_AFTER_FIRST"
```
**Expected:** confirmed status `TODO`, `CURRENT_AFTER_FIRST = CURRENT_BEFORE + 1`
**Extract:** `$TASK_ID`

### Step 4 — Отправить reply email в тот же thread
```bash
TMP2=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: RE: $RUN_ID Release tracking\r\nFrom: duplicate-sender@company.ru\r\nTo: me@test.com\r\n\r\nПродолжаем по той же задаче, это follow-up без нового дедлайна.\r\n" > "$TMP2"
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "duplicate-sender@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file "$TMP2"
rm "$TMP2"
sleep 2
MAIL_ID_2=$(curl -s $MAILDEV_URL/email \
  | jq -r --arg subj "RE: $RUN_ID Release tracking" '[.[] | select(.subject == $subj)] | last | .id')
echo "MAIL_ID_2=$MAIL_ID_2"
```
**Expected:** SMTP 250
**Extract:** `$MAIL_ID_2`

### Step 5 — Дождаться LINK_TO_TASK кандидата
```bash
for i in $(seq 1 18); do
  sleep 5
  TASK_JSON=$(curl -s $MS_URL/api/tasks/pending \
    | jq --arg mid "$MAIL_ID_2" '[.[] | select(.emailId == $mid)] | last')
  if [ "$TASK_JSON" != "null" ]; then
    echo "$TASK_JSON" | jq '{id, pendingType, suggestedTaskId, title}'
    break
  fi
  echo "Attempt $i/18: link candidate not found yet"
done
```
**Expected:** найден pending-кандидат

### Step 6 — Проверить что создан LINK_TO_TASK, а не новая самостоятельная задача
```bash
LINK_PENDING_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq --arg mid "$MAIL_ID_2" '[.[] | select(.emailId == $mid)] | last | .id')
LINK_PENDING_TYPE=$(curl -s $MS_URL/api/tasks/pending \
  | jq -r --arg mid "$MAIL_ID_2" '[.[] | select(.emailId == $mid)] | last | .pendingType')
LINK_SUGGESTED_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq -r --arg mid "$MAIL_ID_2" '[.[] | select(.emailId == $mid)] | last | .suggestedTaskId')

echo "LINK_PENDING_ID=$LINK_PENDING_ID | LINK_PENDING_TYPE=$LINK_PENDING_TYPE | LINK_SUGGESTED_ID=$LINK_SUGGESTED_ID"
```
**Expected:** `LINK_PENDING_TYPE=LINK_TO_TASK`, `LINK_SUGGESTED_ID=$TASK_ID`
**Extract:** `$LINK_PENDING_ID`

### Step 7 — Связать reply с существующей задачей
```bash
curl -s -X POST "$MS_URL/api/tasks/pending/$LINK_PENDING_ID/link" \
  -H "Content-Type: application/json" \
  -d "{\"targetTaskId\":$TASK_ID,\"appendSummary\":false}" \
  | jq '{id, title, status}'
```
**Expected:** HTTP 200, возвращён task `$TASK_ID`

### Step 8 — Количество текущих задач не выросло второй раз
```bash
CURRENT_AFTER_LINK=$(curl -s "$MS_URL/api/tasks?date=$TODAY" | jq 'length')
echo "CURRENT_BEFORE=$CURRENT_BEFORE | AFTER_FIRST=$CURRENT_AFTER_FIRST | AFTER_LINK=$CURRENT_AFTER_LINK"
```
**Expected:** `CURRENT_AFTER_LINK = CURRENT_AFTER_FIRST`

### Step 9 — Timeline исходной задачи содержит EMAIL_LINKED
```bash
curl -s "$MS_URL/api/tasks/$TASK_ID/timeline" | jq '[.[].eventType]'
```
**Expected:** массив содержит `EMAIL_LINKED`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
curl -s -X DELETE "$MS_URL/api/tasks/$TASK_ID" > /dev/null 2>&1 || true
echo "IT-14 cleanup done"
```
