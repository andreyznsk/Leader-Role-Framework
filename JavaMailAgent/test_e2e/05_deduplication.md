# Scenario: Дедупликация — одно письмо обрабатывается только один раз

**service:** JavaMailAgent
**port:** 8080
**priority:** HIGH
**depends_on:** postgres, maildev

## Описание
Проверить что повторный poll цикл не обрабатывает письмо заново —
оно уже есть в processed_emails, агент его пропускает.
REQUEST письмо специально остаётся unread в Maildev (это правильное поведение),
но в processed_emails оно есть → dedup срабатывает.

## Steps

### Step 1 — Очистить Maildev, отправить одно письмо
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null

curl -s --url "smtp://localhost:1025" \
  --mail-from "dedup-test@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Dedup test — задача для проверки идемпотентности
From: dedup-test@company.ru
To: me@test.com

Это письмо для проверки что агент не обработает его дважды.
EOF
echo "Dedup test email sent (→ REQUEST by default)"
```
**Expected:** exit code 0

### Step 2 — Дождаться первой обработки
```bash
echo "Waiting for first processing..."
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(PGPASSWORD=mailagent_password psql \
    -h localhost -U mailagent_user -d leader_framework -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender='dedup-test@company.ru';" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: count=$COUNT"
  if [ "$COUNT" -ge 1 ] 2>/dev/null; then
    echo "  ✅ First processing done"
    break
  fi
done
```
**Expected:** count = 1

### Step 3 — Запомнить processed_at первой обработки
```bash
FIRST_TS=$(PGPASSWORD=mailagent_password psql \
  -h localhost -U mailagent_user -d leader_framework -t \
  -c "SELECT processed_at FROM mailagent.processed_emails WHERE sender='dedup-test@company.ru' ORDER BY processed_at DESC LIMIT 1;" \
  | tr -d ' \n')
echo "First processed_at: $FIRST_TS"
```
**Extract:** `$FIRST_TS`

### Step 4 — Подождать ещё один полный poll цикл
```bash
POLL_INTERVAL=65
echo "Waiting ${POLL_INTERVAL}s for second poll cycle..."
sleep $POLL_INTERVAL
echo "Second poll cycle completed"
```
**Expected:** команда завершилась

### Step 5 — Количество записей не увеличилось (dedup сработал)
```bash
COUNT_AFTER=$(PGPASSWORD=mailagent_password psql \
  -h localhost -U mailagent_user -d leader_framework -t \
  -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender='dedup-test@company.ru';" \
  | tr -d ' ')
echo "Count after second poll: $COUNT_AFTER (expected: 1)"
```
**Expected:** результат `1`

### Step 6 — processed_at не изменился (запись та же самая)
```bash
SECOND_TS=$(PGPASSWORD=mailagent_password psql \
  -h localhost -U mailagent_user -d leader_framework -t \
  -c "SELECT processed_at FROM mailagent.processed_emails WHERE sender='dedup-test@company.ru' ORDER BY processed_at DESC LIMIT 1;" \
  | tr -d ' \n')
echo "First:  $FIRST_TS"
echo "Second: $SECOND_TS"
[ "$FIRST_TS" = "$SECOND_TS" ] && echo "✅ Timestamps match — dedup confirmed" || echo "❌ Different timestamps — processed twice!"
```
**Expected:** оба timestamp совпадают

### Step 7 — Лог содержит пропуск письма (existsByEmailId → skip)
```bash
grep -i "skip\|already\|existsByEmailId\|dedup\|already processed" logs/JavaMailAgent.log | tail -5
```
**Expected:** строка с пропуском уже обработанного письма

## Cleanup
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
echo "Cleanup done"
```
