# Scenario: Mail Linking — IGNORE после initial REQUEST не создаёт задачу

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** HIGH
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
Письмо сначала проходит как `REQUEST` на уровне базовой классификации, но linking-flow
решает `IGNORE`. В результате итоговая обработка должна закончиться как `NOISE`
без создания PENDING-задачи.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Очистить окружение и снять baseline
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
RUN_ID="it13-$(date +%s)"
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
LOG_BEFORE=$(wc -l < logs/JavaMailAgent.log)
echo "RUN_ID=$RUN_ID | PENDING_BEFORE=$PENDING_BEFORE | LOG_BEFORE=$LOG_BEFORE"
```
**Extract:** `$RUN_ID`, `$PENDING_BEFORE`, `$LOG_BEFORE`

### Step 2 — Отправить письмо без action item, но не похожее на базовый NOISE/CAPTURE
```bash
TMPFILE=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: RE: $RUN_ID Release closed\r\nFrom: ignore-sender@company.ru\r\nTo: me@test.com\r\n\r\nПодтверждаю закрытие темы. Действий не требуется, просто информация.\r\n" > "$TMPFILE"

curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "ignore-sender@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file "$TMPFILE"
rm "$TMPFILE"
echo "IGNORE candidate sent"
```
**Expected:** SMTP 250

### Step 3 — Дождаться обработки как NOISE без PENDING
```bash
for i in $(seq 1 18); do
  sleep 5
  NEW_NOISE=$(tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log | grep -c "Classified as NOISE" 2>/dev/null || echo 0)
  PENDING_NOW=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
  echo "Attempt $i/18: noise_logs=$NEW_NOISE | pending_now=$PENDING_NOW"
  if [ "$NEW_NOISE" -ge 1 ] && [ "$PENDING_NOW" -eq "$PENDING_BEFORE" ]; then
    echo "IGNORE/NOISE behavior observed"
    break
  fi
done
```
**Expected:** есть новая строка `Classified as NOISE`, pending count не меняется

### Step 4 — В логах нет создания pending task по этому письму
```bash
tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log \
  | grep -E "Classified as NOISE|Pending task created in memory-service|Mail linking search completed" || true
```
**Expected:** есть `Classified as NOISE`, нет строки про создание pending task

### Step 5 — PENDING очередь не изменилась
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
echo "PENDING before=$PENDING_BEFORE | after=$PENDING_AFTER"
```
**Expected:** `PENDING_AFTER = PENDING_BEFORE`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
echo "IT-13 cleanup done"
```
