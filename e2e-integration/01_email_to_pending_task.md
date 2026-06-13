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
- Maildev доступен через Docker bridge (см. env ниже)

## Environment

```bash
export MA_URL="http://localhost:8080"
export MS_URL="http://localhost:8082"
export MAILDEV_URL="http://172.80.2.1:18080"   # Docker bridge IP, не localhost
export MAILDEV_SMTP="172.80.2.1:1025"
```

> ⚠️ Maildev слушает на Docker bridge `172.80.2.1`, а не на `localhost`.
> Порт UI/API: 18080. Порт SMTP: 1025.

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

### Step 3 — Отправить REQUEST письмо
```bash
TMPFILE=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: $RUN_ID Нужна ревью архитектуры — дедлайн завтра\r\nFrom: boss@company.ru\r\nTo: me@test.com\r\n\r\nПривет, нужно ревью архитектурного решения.\r\nДедлайн — завтра утром. Это важно для релиза.\r\n" > "$TMPFILE"

curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "boss@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file "$TMPFILE"
rm "$TMPFILE"
echo "Email sent with RUN_ID=$RUN_ID"
```
**Expected:** curl exit code 0, письмо появляется в Maildev (`curl $MAILDEV_URL/email | jq 'length'` >= 1)

### Step 4 — Дождаться обработки MailAgent (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  PENDING_NOW=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
  echo "  Attempt $i/18: PENDING=$PENDING_NOW (baseline=$PENDING_BEFORE)"
  [ "$PENDING_NOW" -gt "$PENDING_BEFORE" ] && echo "  ✅ New PENDING task appeared" && break
done
```
**Expected:** `PENDING_NOW > PENDING_BEFORE`

> Вместо psql используется опрос `/api/tasks/pending` — psql-client может не быть на хосте.

### Step 5 — Задача появилась в PENDING очереди MemoryService
```bash
PENDING_AFTER=$(curl -s $MS_URL/api/tasks/pending | jq 'length')
NEW=$((PENDING_AFTER - PENDING_BEFORE))
echo "New PENDING tasks: $NEW (total: $PENDING_AFTER)"
```
**Expected:** `$PENDING_AFTER > $PENDING_BEFORE`

### Step 6 — Новая задача: статус PENDING, emailId совпадает с нашим письмом
```bash
TASK_JSON=$(curl -s $MS_URL/api/tasks/pending \
  | jq 'sort_by(.id) | last')
TASK_ID=$(echo "$TASK_JSON" | jq -r '.id')
echo "$TASK_JSON" | jq '{id, title, status, priority, emailId}'
echo "Task ID: $TASK_ID"
```
**Expected:** `"status":"PENDING"`

> **Примечание по приоритету:** при `mock.agent=true` приоритет всегда `NORMAL` — mock не
> анализирует текст письма. Для проверки HIGH-приоритета нужен реальный агент (`mock.agent=false`).
> Поле `sender` отсутствует в Task DTO (хранится только `emailId`).

**Extract:** `id` → `$TASK_ID`

### Step 7 — PENDING задача НЕ в /api/context (не видна агенту)
```bash
CONTEXT_CONTAINS=$(curl -s $MS_URL/api/context | grep -c "$TASK_ID" || true)
echo "Context contains PENDING task id: $CONTEXT_CONTAINS"
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
**Expected:** `"status":"TODO"`

### Step 10 — Завершить задачу: TODO → DONE
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/done" | jq '{id, status}'
```
**Expected:** `"status":"DONE"`

## Cleanup
```bash
curl -s -X DELETE $MAILDEV_URL/email/all > /dev/null
curl -s -X DELETE "$MS_URL/api/tasks/$TASK_ID" > /dev/null
echo "IT-01 cleanup done"
```
