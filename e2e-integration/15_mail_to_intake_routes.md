# Scenario: Mail → Intake Gateway — TASK / NOTE / NOISE routes

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** CRITICAL
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание

Проверяет основной маршрут после перевода mail-derived действий в Intake Gateway:

- письмо `REQUEST` создаёт intake item с `suggestedRoute=TASK`
- письмо `CAPTURE` создаёт intake item с `suggestedRoute=NOTE`
- письмо `DRAFT` создаёт intake item с `suggestedRoute=NOISE`
- письмо `NOISE` создаёт intake item с `suggestedRoute=NOISE`

Сценарий специально не проверяет ручной `apply/reject` логики intake. Для этого уже есть
`JavaMemoryService/test_e2e/19_intake_gateway_manual_routing.md`.

> Ограничение mock-классификатора: в режиме `mock.agent=true` mail keyword-flow стабильно
> воспроизводит `REQUEST`, `CAPTURE`, `DRAFT`, `NOISE`. Для отдельного `NOTICE -> RAG`
> используется сценарий `e2e-integration/09_mail_notice_to_rag_document.md`.

## Preconditions

- `JavaMailAgent` запущен с `memory.service.enabled=true`, `mock.agent=true`
- `JavaMemoryService` запущен на `:8082`
- `source e2e-integration/env.sh`
- `jq` установлен

## Steps

### Step 1 — Оба сервиса живы
```bash
MA=$(curl -s -o /dev/null -w "%{http_code}" $MA_URL/actuator/health)
MS=$(curl -s -o /dev/null -w "%{http_code}" $MS_URL/actuator/health)
echo "MailAgent: $MA | MemoryService: $MS"
```
**Expected:** оба `200`

### Step 2 — Очистить Maildev и снять baseline intake queue
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
RUN_ID="it15-$(date +%s)"
INTAKE_BEFORE=$(curl -s "$MS_URL/api/intake?status=NEW" | jq 'length')
LOG_BEFORE=$(wc -l < logs/mail-agent.log)
echo "RUN_ID=$RUN_ID | INTAKE_BEFORE=$INTAKE_BEFORE | LOG_BEFORE=$LOG_BEFORE"
```
**Extract:** `$RUN_ID`, `$INTAKE_BEFORE`, `$LOG_BEFORE`

### Step 3 — Отправить REQUEST письмо (→ TASK)
```bash
TMP1=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\nSubject: %s Нужен отчёт по релизу — дедлайн завтра\r\nFrom: req-%s@company.ru\r\nTo: me@test.com\r\n\r\nПривет, подготовь отчёт по релизу.\r\nДедлайн — завтра утром, это важно.\r\n" "$RUN_ID" "$RUN_ID" > "$TMP1"
curl -s -o /dev/null -w "REQUEST SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "req-$RUN_ID@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMP1"
rm "$TMP1"
```
**Expected:** SMTP `250`

### Step 4 — Отправить CAPTURE письмо (→ NOTE)
```bash
TMP2=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\nSubject: FYI: %s переезд release checklist\r\nFrom: capture-%s@company.ru\r\nTo: me@test.com\r\n\r\nК сведению: FYI по новому release checklist.\r\nСохранить как полезную заметку, действий сейчас не требуется.\r\n" "$RUN_ID" "$RUN_ID" > "$TMP2"
curl -s -o /dev/null -w "CAPTURE SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "capture-$RUN_ID@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMP2"
rm "$TMP2"
```
**Expected:** SMTP `250`

### Step 5 — Отправить DRAFT письмо (→ NOISE route inside intake)
```bash
TMP3=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: 8bit\r\nSubject: Re: %s коммерческое предложение\r\nFrom: draft-%s@external.com\r\nTo: me@test.com\r\n\r\nНужно ответное письмо клиенту.\r\nПодготовь черновик ответа с подтверждением условий.\r\n" "$RUN_ID" "$RUN_ID" > "$TMP3"
curl -s -o /dev/null -w "DRAFT SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "draft-$RUN_ID@external.com" --mail-rcpt "me@test.com" --upload-file "$TMP3"
rm "$TMP3"
```
**Expected:** SMTP `250`

### Step 6 — Отправить NOISE письмо (→ NOISE)
```bash
TMP4=$(mktemp)
cat > "$TMP4" <<EOF
Subject: $RUN_ID Build #700 passed
From: noise-$RUN_ID@jenkins.local
To: me@test.com

Build #700 passed successfully. Duration: 52s.
EOF
curl -s -o /dev/null -w "NOISE SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "noise-$RUN_ID@jenkins.local" --mail-rcpt "me@test.com" --upload-file "$TMP4"
rm "$TMP4"
```
**Expected:** SMTP `250`

### Step 7 — Дождаться создания 4 новых intake items (до 120 сек)
```bash
for i in $(seq 1 24); do
  sleep 5
  INTAKE_MATCHED=$(curl -s "$MS_URL/api/intake?status=NEW" | jq --arg run "$RUN_ID" '[.[] | select(.sourceText != null and (.sourceText | contains($run)))] | length')
  NEW_LOG=$(tail -n "+$((LOG_BEFORE+1))" logs/mail-agent.log | grep -c "Intake item created in memory-service" 2>/dev/null || echo 0)
  echo "  Attempt $i/24: intake_matched=$INTAKE_MATCHED | intake_log_hits=$NEW_LOG"
  [ "$INTAKE_MATCHED" -ge 4 ] && [ "$NEW_LOG" -ge 4 ] && echo "  ✅ 4 intake items created" && break
done
```
**Expected:** найдено `4` новых intake items

> Если здесь стабильно `0`, первым делом проверь `memory-service` URL в `mail-agent`.
> Типичный сбой: `mail-agent` пытается писать в `http://localhost:8090/api/intake`
> вместо реального `http://localhost:8082/api/intake`.

### Step 8 — В очереди intake есть ровно 4 mail-derived item с нашим RUN_ID
```bash
ITEMS=$(curl -s "$MS_URL/api/intake?status=NEW" | jq --arg run "$RUN_ID" '[.[] | select(.sourceText != null and (.sourceText | contains($run)))]')
echo "$ITEMS" | jq 'length'
echo "$ITEMS" | jq '[.[] | {id, sourceType, suggestedRoute, status, createdBy}]'
```
**Expected:** `length = 4`, у всех `sourceType = MAIL`, `status = NEW`, `createdBy = mail-agent`

### Step 9 — TASK route создан для REQUEST письма
```bash
echo "$ITEMS" | jq --arg sender "req-$RUN_ID@company.ru" '
  [.[] | select(.sourcePayload.from == $sender)] | first |
  {
    id,
    suggestedRoute,
    sourceType,
    title: .suggestedPayload.title,
    priority: .suggestedPayload.priority,
    emailId: .suggestedPayload.emailId
  }'
```
**Expected:** `suggestedRoute = TASK`, `title` не пустой, `priority` задан

### Step 10 — NOTE route создан для CAPTURE письма
```bash
echo "$ITEMS" | jq --arg sender "capture-$RUN_ID@company.ru" '
  [.[] | select(.sourcePayload.from == $sender)] | first |
  {
    id,
    suggestedRoute,
    sourceType,
    title: .suggestedPayload.title,
    text: .suggestedPayload.text
  }'
```
**Expected:** `suggestedRoute = NOTE`, `text` не пустой

### Step 11 — DRAFT и NOISE дали два item с suggestedRoute=NOISE
```bash
echo "$ITEMS" | jq '
  [.[] | select(.suggestedRoute == "NOISE")] |
  map({id, sender: .sourcePayload.from, title: .suggestedPayload.title, text: .suggestedPayload.text})'
echo "$ITEMS" | jq '[.[] | select(.suggestedRoute == "NOISE")] | length'
```
**Expected:** длина `2`

### Step 12 — Распределение по route корректное: TASK=1, NOTE=1, NOISE=2
```bash
echo "$ITEMS" | jq 'group_by(.suggestedRoute) | map({route: .[0].suggestedRoute, count: length})'
TASK_COUNT=$(echo "$ITEMS" | jq '[.[] | select(.suggestedRoute == "TASK")] | length')
NOTE_COUNT=$(echo "$ITEMS" | jq '[.[] | select(.suggestedRoute == "NOTE")] | length')
NOISE_COUNT=$(echo "$ITEMS" | jq '[.[] | select(.suggestedRoute == "NOISE")] | length')
echo "TASK=$TASK_COUNT | NOTE=$NOTE_COUNT | NOISE=$NOISE_COUNT"
```
**Expected:** `TASK=1`, `NOTE=1`, `NOISE=2`

### Step 13 — Лог содержит все 4 успешные записи в intake
```bash
tail -n "+$((LOG_BEFORE+1))" logs/mail-agent.log \
  | grep -E "Intake item created in memory-service|Poll finished" | tail -20
```
**Expected:** минимум `4` строки `Intake item created in memory-service`

## Cleanup
```bash
echo "$ITEMS" | jq -r '.[].id' | while read -r id; do
  [ -n "$id" ] || continue
  curl -s -X POST "$MS_URL/api/intake/$id/reject" \
    -H "Content-Type: application/json" \
    -d '{"reason":"e2e cleanup","reviewedBy":"codex"}' > /dev/null
done
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
echo "IT-15 cleanup done"
```
