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
echo "PENDING before: $PENDING_BEFORE | Captures before: $CAPTURES_BEFORE"
```
**Extract:** `$PENDING_BEFORE`, `$CAPTURES_BEFORE`

### Step 3 — Отправить FYI письмо (FYI → MockClaudeRunner → CAPTURE)
```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "team@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: FYI: переезд на новый Kubernetes кластер с 1 июля
From: team@company.ru
To: me@test.com

К сведению: с 1 июля команда инфраструктуры переезжает на новый кластер.
Никаких действий не требуется — просто будьте в курсе при следующем деплое.
EOF
echo "CAPTURE email sent (contains: FYI)"
```
**Expected:** exit code 0

### Step 4 — Дождаться обработки MailAgent (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender='team@company.ru' AND agent_type='CAPTURE';" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: CAPTURE count=$COUNT"
  [ "$COUNT" -ge 1 ] 2>/dev/null && echo "  ✅ CAPTURE processed" && break
done
```
**Expected:** count >= 1

### Step 5 — Запись в processed_emails с agent_type=CAPTURE
```bash
psql -h $PGHOST -U $PGUSER -d $PGDATABASE \
  -c "SELECT sender, subject, agent_type FROM mailagent.processed_emails WHERE sender='team@company.ru' ORDER BY processed_at DESC LIMIT 1;"
```
**Expected:** `agent_type=CAPTURE`

### Step 6 — Capture появился в MemoryService с source=email
```bash
CAPTURES_AFTER=$(curl -s $MS_URL/api/capture/today | jq 'length')
NEW=$((CAPTURES_AFTER - CAPTURES_BEFORE))
echo "New captures: $NEW"
CAPTURE_ID=$(curl -s $MS_URL/api/capture/today \
  | jq '[.[] | select(.source == "email")] | last | .id')
echo "Capture ID: $CAPTURE_ID"
curl -s $MS_URL/api/capture/today \
  | jq '[.[] | select(.source == "email")] | last | {id, source, status, rawText}'
```
**Expected:** `$NEW >= 1`, объект с `"source":"email"`, `"status":"PENDING"`
**Extract:** `$CAPTURE_ID`

### Step 7 — Задача НЕ создана в PENDING (CAPTURE ≠ REQUEST)
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
[ "$PENDING_AFTER" = "$PENDING_BEFORE" ] \
  && echo "✅ No new PENDING tasks" \
  || echo "❌ Unexpected PENDING task created!"
```
**Expected:** `$PENDING_AFTER == $PENDING_BEFORE`

### Step 8 — Письмо НЕ помечено прочитанным (CAPTURE остаётся unread)
```bash
curl -s $MAILDEV_URL/email \
  | jq '[.[] | select(.from[0].address == "team@company.ru" and .read == false)] | length'
```
**Expected:** `1`

### Step 9 — Повторный poll не обрабатывает письмо (dedup)
```bash
echo "Waiting for second poll cycle (dedup check)..."
sleep 65
COUNT_AFTER=$(psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
  -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender='team@company.ru';" \
  2>/dev/null | tr -d ' ')
[ "$COUNT_AFTER" = "1" ] && echo "✅ Dedup OK (count=1)" || echo "❌ Processed twice (count=$COUNT_AFTER)"
```
**Expected:** count = 1

### Step 10 — Запустить классификацию capture
```bash
curl -s -X POST $MS_URL/api/capture/process-now | jq '{total, routed}'
```
**Expected:** HTTP 200, `routed >= 1`

### Step 11 — Capture переведён в PROCESSED
```bash
psql -h $PGHOST -U $PGUSER -d $PGDATABASE \
  -c "SELECT id, status, classified, routed_to FROM memory.captures WHERE id=$CAPTURE_ID;"
```
**Expected:** `status=PROCESSED`, `classified` и `routed_to` непустые

### Step 12 — Файл перемещён в capture-inbox/processed/
```bash
TODAY=$(date +%Y-%m-%d)
ls capture-inbox/processed/$TODAY/ 2>/dev/null | head -5
```
**Expected:** файл присутствует

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
echo "IT-08 cleanup done"
```
