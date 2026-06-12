# Scenario: Полный email-цикл — письмо → REQUEST → PENDING → TODO → DONE

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** CRITICAL
**depends_on:** maildev, postgres
**profile:** local, `memory.service.enabled=true`, `mock.agent=true`

## Описание
Самый важный сквозной маршрут системы. Письмо проходит путь от SMTP до
подтверждённой задачи в плане дня и её завершения.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`, `mock.agent=true`
- JavaMemoryService запущен на :8082
- Maildev доступен на $MAILDEV_URL

## Steps

### Step 1 — Убедиться что оба сервиса живы
```bash
MA=$(curl -s -o /dev/null -w "%{http_code}" $MA_URL/actuator/health)
MS=$(curl -s -o /dev/null -w "%{http_code}" $MS_URL/actuator/health)
echo "MailAgent: $MA | MemoryService: $MS"
```
**Expected:** оба 200

### Step 2 — Очистить окружение и запомнить baseline
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
RUN_ID="it01-$(date +%s)"
TODAY=$(date +%Y-%m-%d)
PENDING_BEFORE=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
echo "RUN_ID=$RUN_ID | PENDING before: $PENDING_BEFORE"
```
**Extract:** `$RUN_ID`, `$TODAY`, `$PENDING_BEFORE`

### Step 3 — Отправить REQUEST письмо (нет BUILD/PASSED → REQUEST, ДЕДЛАЙН → HIGH)
```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "boss@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: $RUN_ID Нужна ревью архитектуры — дедлайн завтра
From: boss@company.ru
To: me@test.com

Привет, нужно ревью архитектурного решения.
Дедлайн — завтра утром. Это важно для релиза.
EOF
echo "Email sent with RUN_ID=$RUN_ID"
```
**Expected:** exit code 0

### Step 4 — Дождаться обработки MailAgent (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender='boss@company.ru';" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: count=$COUNT"
  [ "$COUNT" -ge 1 ] 2>/dev/null && echo "  ✅ Email processed" && break
done
```
**Expected:** count >= 1

### Step 5 — Задача появилась в PENDING очереди MemoryService
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
NEW=$((PENDING_AFTER - PENDING_BEFORE))
echo "New PENDING tasks: $NEW (total: $PENDING_AFTER)"
```
**Expected:** `$PENDING_AFTER > $PENDING_BEFORE`

### Step 6 — Новая задача: статус PENDING, приоритет HIGH
```bash
TASK_JSON=$(curl -s $MS_URL/api/tasks/pending \
  | jq '[.[] | select(.sender == "boss@company.ru")] | last')
TASK_ID=$(echo "$TASK_JSON" | jq -r '.id')
echo "$TASK_JSON" | jq '{id, title, status, priority, sender}'
echo "Task ID: $TASK_ID"
```
**Expected:** `"status":"PENDING"`, `"priority":"HIGH"`, `"sender":"boss@company.ru"`
**Extract:** `id` → `$TASK_ID`

### Step 7 — PENDING задача НЕ в /api/context (не видна агенту)
```bash
CONTEXT_CONTAINS=$(curl -s $MS_URL/api/context | grep -c "boss@company.ru" || true)
echo "Context contains PENDING task: $CONTEXT_CONTAINS"
```
**Expected:** 0

### Step 8 — Подтвердить задачу: PENDING → TODO
```bash
RESULT=$(curl -s -w "\n%{http_code}" -X POST "$MS_URL/api/tasks/$TASK_ID/confirm")
echo "HTTP: $(echo "$RESULT" | tail -1) | Status: $(echo "$RESULT" | head -n -1 | jq -r '.status')"
```
**Expected:** HTTP 200, `"status":"TODO"`

### Step 9 — Задача видна в плане дня со статусом TODO
```bash
curl -s "$MS_URL/api/tasks?date=$TODAY&status=TODO" \
  | jq "[.[] | select(.id == $TASK_ID)] | first | {id, title, status, priority}"
```
**Expected:** `"status":"TODO"`, `"priority":"HIGH"`

### Step 10 — Завершить задачу: TODO → DONE
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/done" | jq '{id, status}'
```
**Expected:** `"status":"DONE"`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/delete" > /dev/null 2>&1 || true
echo "IT-01 cleanup done"
```
