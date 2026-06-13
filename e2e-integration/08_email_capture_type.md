# Scenario: Email CAPTURE — FYI письмо → POST /api/capture в JavaMemoryService

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** HIGH
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`
**cr:** CR-MAIL-003

## Описание
Письмо с полезной информацией без срочного действия (FYI / "К сведению").
MockClaudeRunner: ключевое слово `FYI` → тип `CAPTURE`.

Полный сквозной путь:
FYI письмо → MailAgent → processed_emails(CAPTURE)
           → POST /api/capture → captures(PENDING, source=email)
           → задача НЕ создана → письмо unread → dedup OK
           → process-now → captures(PROCESSED)

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен с профилем `local,e2e`
- CR-MAIL-003 реализован (AgentResponseType.CAPTURE существует)

## Steps

### Step 1 — Оба сервиса живы
```bash
MA=$(curl -s -o /dev/null -w "%{http_code}" $MA_URL/actuator/health)
MS=$(curl -s -o /dev/null -w "%{http_code}" $MS_URL/actuator/health)
echo "MailAgent: $MA | MemoryService: $MS"
```
**Expected:** оба 200

### Step 2 — Очистить окружение и запомнить baseline
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
CAPTURES_BEFORE=$(curl -s $MS_URL/api/capture/today | jq 'length')
LOG_BEFORE=$(wc -l < logs/JavaMailAgent.log)
echo "PENDING before: $PENDING_BEFORE | Captures before: $CAPTURES_BEFORE | Log lines: $LOG_BEFORE"
```
**Extract:** `$PENDING_BEFORE`, `$CAPTURES_BEFORE`, `$LOG_BEFORE`

### Step 3 — Отправить FYI письмо (FYI → MockClaudeRunner → CAPTURE)
```bash
TMPFILE=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: FYI: переезд на новый Kubernetes кластер с 1 июля\r\nFrom: team@company.ru\r\nTo: me@test.com\r\n\r\nК сведению: с 1 июля команда инфраструктуры переезжает на новый кластер.\r\nНикаких действий не требуется — просто будьте в курсе при следующем деплое.\r\n" > "$TMPFILE"
curl -s -o /dev/null -w "SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "team@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMPFILE"
rm "$TMPFILE"
echo "CAPTURE email sent (subject contains: FYI)"
```
**Expected:** SMTP 250

### Step 4 — Дождаться обработки MailAgent (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  NEW_LOG=$(tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log | grep -c "CAPTURE" 2>/dev/null || echo 0)
  echo "  Attempt $i/18: CAPTURE log lines=$NEW_LOG"
  [ "$NEW_LOG" -ge 1 ] && echo "  ✅ CAPTURE processed" && break
done
```
**Expected:** лог содержит строку с CAPTURE

> Заменяет `psql`-проверку — psql-client может не быть на хосте.

### Step 5 — Классификация CAPTURE подтверждена в логе
```bash
tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log \
  | grep -E "CAPTURE|Classified as" | head -5
```
**Expected:** `Classified as CAPTURE` присутствует

### Step 6 — Capture появился в MemoryService с source=email
```bash
CAPTURES_AFTER=$(curl -s $MS_URL/api/capture/today | jq 'length')
NEW=$((CAPTURES_AFTER - CAPTURES_BEFORE))
echo "New captures: $NEW"
CAPTURE_ID=$(curl -s $MS_URL/api/capture/today \
  | python3 -c "
import sys, json
data = json.loads(sys.stdin.read())
for c in reversed(data):
    if c.get('source') == 'email':
        print(c['id'])
        break
")
echo "Capture ID: $CAPTURE_ID"
curl -s "$MS_URL/api/capture/$CAPTURE_ID" 2>/dev/null \
  | python3 -c "import sys,json; d=json.loads(sys.stdin.read()); print({k:d.get(k) for k in ['id','source','status']})" 2>/dev/null || \
  curl -s $MS_URL/api/capture/today | python3 -c "
import sys, json
data = json.loads(sys.stdin.read())
for c in reversed(data):
    if c.get('source') == 'email':
        print({k:c.get(k) for k in ['id','source','status']})
        break
"
```
**Expected:** `$NEW >= 1`, объект с `source=email`, `status=PENDING`
**Extract:** `$CAPTURE_ID`

### Step 7 — Задача НЕ создана в PENDING (CAPTURE ≠ REQUEST)
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
[ "$PENDING_AFTER" = "$PENDING_BEFORE" ] \
  && echo "✅ No new PENDING tasks" \
  || echo "❌ Unexpected PENDING task created! (before=$PENDING_BEFORE, after=$PENDING_AFTER)"
```
**Expected:** `$PENDING_AFTER == $PENDING_BEFORE`

### Step 8 — Письмо НЕ помечено прочитанным (CAPTURE остаётся unread)
```bash
tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log \
  | grep -E "marked as read|team@company" | head -5
echo "---"
# Maildev REST read-флаг ненадёжен при IMAP-маркировке — верифицируем через лог:
MARKED=$(tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log | grep -c "marked as read" 2>/dev/null || echo 0)
[ "$MARKED" = "0" ] && echo "✅ Email NOT marked as read (CAPTURE)" || echo "❌ Email was marked as read (unexpected)"
```
**Expected:** `marked as read` НЕ появляется для CAPTURE

> Maildev REST `.read` не обновляется при IMAP `\Seen` — проверка через лог.

### Step 9 — Повторный poll не обрабатывает письмо (dedup)
```bash
echo "Waiting for second poll cycle (dedup check)..."
LOG_BEFORE_DEDUP=$(wc -l < logs/JavaMailAgent.log)
sleep 65
NEW_CAPTURE=$(tail -n "+$((LOG_BEFORE_DEDUP+1))" logs/JavaMailAgent.log \
  | grep -c "Classified as CAPTURE" 2>/dev/null || echo 0)
CAPTURES_DEDUP=$(curl -s $MS_URL/api/capture/today | jq 'length')
[ "$NEW_CAPTURE" = "0" ] && echo "✅ Dedup OK — not re-processed" || echo "❌ Re-processed! (count=$NEW_CAPTURE)"
echo "Total captures after dedup wait: $CAPTURES_DEDUP (before: $CAPTURES_BEFORE)"
```
**Expected:** `NEW_CAPTURE = 0` (второй poll не создал новую capture запись)

### Step 10 — Запустить классификацию capture
```bash
curl -s -X POST $MS_URL/api/capture/process-now | jq '{total, routed}'
```
**Expected:** HTTP 200, `routed >= 1`

### Step 11 — Capture переведён в PROCESSED (через API capture/today)
```bash
CAPTURES_NOW=$(curl -s $MS_URL/api/capture/today)
STATUS=$(echo "$CAPTURES_NOW" | python3 -c "
import sys, json
data = json.loads(sys.stdin.read())
for c in data:
    if str(c.get('id')) == '$CAPTURE_ID':
        print(c.get('status'))
        break
" 2>/dev/null || echo "unknown")
echo "Capture $CAPTURE_ID status: $STATUS"
[ "$STATUS" = "PROCESSED" ] && echo "✅ Status PROCESSED" || echo "❌ Status=$STATUS (expected PROCESSED)"
```
**Expected:** `status=PROCESSED`

> Заменяет `psql`-проверку.

### Step 12 — Файл перемещён в capture-inbox/processed/
```bash
TODAY=$(date +%Y-%m-%d)
ls capture-inbox/processed/$TODAY/ 2>/dev/null | head -5
PROC_COUNT=$(ls capture-inbox/processed/$TODAY/ 2>/dev/null | wc -l)
echo "Files in processed/$TODAY: $PROC_COUNT"
[ "$PROC_COUNT" -ge 1 ] && echo "✅ Files in processed/" || echo "❌ No files in processed/"
```
**Expected:** файл присутствует

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
echo "IT-08 cleanup done"
```
