# Scenario: Интеграция с JavaMemoryService — REQUEST → PENDING задача

**service:** JavaMailAgent + JavaMemoryService
**ports:** 8080, 8082
**priority:** HIGH
**depends_on:** postgres, maildev
**profile:** local с `memory.service.enabled=true`

## Описание
Полный сквозной поток: письмо → JavaMailAgent классифицирует как REQUEST →
POST /api/tasks/pending в JavaMemoryService → задача видна в UI.

## Preconditions
- JavaMailAgent запущен с `memory.service.enabled=true`
- JavaMemoryService запущен на :8082
- mock.agent=true (MockClaudeRunner возвращает REQUEST)

## Steps

### Step 1 — Убедиться что оба сервиса живы
```bash
MA=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health)
MS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health)
echo "MailAgent: $MA | MemoryService: $MS"
```
**Expected:** оба возвращают 200

### Step 2 — Запомнить количество PENDING задач до теста
```bash
PENDING_BEFORE=$(curl -s http://localhost:8082/api/tasks/pending | jq 'length')
echo "PENDING before: $PENDING_BEFORE"
```
**Extract:** `$PENDING_BEFORE`

### Step 3 — Очистить Maildev и отправить REQUEST письмо
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null

curl -s --url "smtp://localhost:1025" \
  --mail-from "product@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Срочно: ревью дизайна API до завтра
From: product@company.ru
To: me@test.com

Привет! Нужно срочно согласовать дизайн нового API.
Дедлайн — завтра утром. Это важно для релиза.
EOF
echo "REQUEST email sent"
```
**Expected:** exit code 0

### Step 4 — Дождаться обработки (до 90 сек)
```bash
echo "Waiting for processing..."
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(PGPASSWORD=mailagent_password psql \
    -h localhost -U mailagent_user -d leader_framework -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender='product@company.ru';" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: processed=$COUNT"
  if [ "$COUNT" -ge 1 ] 2>/dev/null; then
    echo "  ✅ Email processed by MailAgent"
    break
  fi
done
```
**Expected:** count >= 1

### Step 5 — Задача появилась в PENDING очереди JavaMemoryService
```bash
PENDING_AFTER=$(curl -s http://localhost:8082/api/tasks/pending | jq 'length')
echo "PENDING after: $PENDING_AFTER (was: $PENDING_BEFORE)"
NEW_TASKS=$((PENDING_AFTER - PENDING_BEFORE))
echo "New PENDING tasks: $NEW_TASKS"
```
**Expected:** `$PENDING_AFTER` > `$PENDING_BEFORE` (появилась хотя бы одна новая задача)

### Step 6 — Новая задача содержит данные из письма
```bash
curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.sender == "product@company.ru")] | last | {id, title, sender, priority, status}'
```
**Expected:** объект с `"status":"PENDING"`, `"sender":"product@company.ru"`, `title` непустой

### Step 7 — Лог MailAgent содержит успешный вызов memory-service
```bash
grep -i "memory-service\|pending task created\|POST.*tasks/pending" logs/JavaMailAgent.log | tail -5
```
**Expected:** строка содержит `Pending task created` или `memory-service`

### Step 8 — Подтвердить задачу в MemoryService (cleanup)
```bash
TASK_ID=$(curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.sender == "product@company.ru")] | last | .id')
echo "Confirming task $TASK_ID"
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/confirm" | jq '{id, status}'
```
**Expected:** `"status":"TODO"`

## Cleanup
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
# Задача переведена в TODO в Step 8 — дополнительная очистка не требуется
echo "Cleanup done"
```
