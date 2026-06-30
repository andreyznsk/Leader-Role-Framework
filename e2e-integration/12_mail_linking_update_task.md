# Scenario: Mail Linking — UPDATE_TASK обновляет существующую задачу

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** HIGH
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
Существующая задача уже есть в MemoryService. Ответное письмо с новым сроком
должно создать `PENDING`-кандидат типа `UPDATE_TASK`, после link-action
обновить description target task и записать timeline events.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Очистить окружение и создать target task
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
RUN_ID="it12-$(date +%s)"
TODAY=$(date +%Y-%m-%d)

TARGET=$(curl -s -X POST "$MS_URL/api/tasks" \
  -H "Content-Type: application/json" \
  -d "{
    \"title\":\"$RUN_ID Release tracking\",
    \"description\":\"Initial release description for $RUN_ID\",
    \"date\":\"$TODAY\",
    \"priority\":\"HIGH\",
    \"source\":\"MANUAL\"
  }")
TARGET_ID=$(echo "$TARGET" | jq -r '.id')
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')

echo "RUN_ID=$RUN_ID | TARGET_ID=$TARGET_ID | PENDING_BEFORE=$PENDING_BEFORE"
```
**Expected:** `TARGET_ID` числовой
**Extract:** `$RUN_ID`, `$TODAY`, `$TARGET_ID`, `$PENDING_BEFORE`

### Step 2 — Отправить reply email с обновлением срока
```bash
TMPFILE=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: RE: $RUN_ID Release tracking\r\nFrom: update-sender@company.ru\r\nTo: me@test.com\r\n\r\nНовый срок по релизу: пятница. Дедлайн сдвигаем на Friday.\r\n" > "$TMPFILE"

curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "update-sender@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file "$TMPFILE"
rm "$TMPFILE"

sleep 2
MAIL_ID=$(curl -s $MAILDEV_URL/email | jq -r '[.[] | select(.from[0].address == "update-sender@company.ru")] | last | .id')
echo "MAIL_ID=$MAIL_ID"
```
**Expected:** SMTP 250
**Extract:** `$MAIL_ID`

### Step 3 — Дождаться появления pending update-кандидата
```bash
for i in $(seq 1 18); do
  sleep 5
  TASK_JSON=$(curl -s $MS_URL/api/tasks/pending \
    | jq --arg mid "$MAIL_ID" '[.[] | select(.emailId == $mid)] | last')
  if [ "$TASK_JSON" != "null" ]; then
    echo "$TASK_JSON" | jq '{id, pendingType, suggestedTaskId, proposedDescriptionAppend}'
    break
  fi
  echo "Attempt $i/18: pending candidate not found yet"
done
```
**Expected:** найден JSON pending-кандидата

### Step 4 — Проверить что решение равно UPDATE_TASK
```bash
PENDING_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq --arg mid "$MAIL_ID" '[.[] | select(.emailId == $mid)] | last | .id')
PENDING_TYPE=$(curl -s $MS_URL/api/tasks/pending \
  | jq -r --arg mid "$MAIL_ID" '[.[] | select(.emailId == $mid)] | last | .pendingType')
SUGGESTED_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq -r --arg mid "$MAIL_ID" '[.[] | select(.emailId == $mid)] | last | .suggestedTaskId')

echo "PENDING_ID=$PENDING_ID | PENDING_TYPE=$PENDING_TYPE | SUGGESTED_ID=$SUGGESTED_ID"
```
**Expected:** `PENDING_TYPE=UPDATE_TASK`, `SUGGESTED_ID=$TARGET_ID`
**Extract:** `$PENDING_ID`

### Step 5 — Применить update через link endpoint
```bash
curl -s -X POST "$MS_URL/api/tasks/pending/$PENDING_ID/link" \
  -H "Content-Type: application/json" \
  -d "{\"targetTaskId\":$TARGET_ID,\"appendSummary\":true}" \
  | jq '{id, title, status}'
```
**Expected:** HTTP 200, возвращён target task

### Step 6 — Pending исчез, description обновлён
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending \
  | jq --arg mid "$MAIL_ID" '[.[] | select(.emailId == $mid)] | length')
DESCRIPTION=$(curl -s "$MS_URL/api/tasks/$TARGET_ID/description" | jq -r '.contentMd')

echo "Pending left: $PENDING_AFTER"
echo "$DESCRIPTION"
```
**Expected:** `PENDING_AFTER = 0`, description содержит `Mock update from email`

### Step 7 — Timeline содержит EMAIL_LINKED и DESCRIPTION_UPDATED
```bash
curl -s "$MS_URL/api/tasks/$TARGET_ID/timeline" \
  | jq '[.[].eventType]'
```
**Expected:** массив содержит `EMAIL_LINKED` и `DESCRIPTION_UPDATED`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
curl -s -X DELETE "$MS_URL/api/tasks/$TARGET_ID" > /dev/null 2>&1 || true
echo "IT-12 cleanup done"
```
