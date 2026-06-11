# Scenario: Несколько писем за один poll цикл — смешанные типы

**service:** JavaMailAgent
**port:** 8080
**priority:** MEDIUM
**depends_on:** postgres, maildev
**profile:** local (mock.agent=true)

## Описание
Отправить 3 письма с разным содержимым одновременно.
MockClaudeRunner логика:
- "Build #400 passed / Duration:" → NOISE
- "дедлайн" → REQUEST + HIGH
- "ОТВЕТН" → DRAFT (письмо с просьбой написать ответ)

Проверить что все три обработаны и типы соответствуют ожиданиям.

## Steps

### Step 1 — Очистить Maildev
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
echo "Maildev cleared"
```

### Step 2 — Письмо 1: CI уведомление (BUILD + passed + Duration → NOISE)
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "ci@jenkins.local" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Build #400 passed
From: ci@jenkins.local
To: me@test.com

Build #400 completed successfully. Duration: 3m 12s.
EOF
echo "Email 1 sent: CI (→ NOISE)"
```
**Expected:** exit code 0

### Step 3 — Письмо 2: задача с дедлайном (нет BUILD → REQUEST, дедлайн → HIGH)
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "manager@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Нужен отчёт — дедлайн пятница
From: manager@company.ru
To: me@test.com

Привет, подготовь квартальный отчёт. Дедлайн — пятница.
EOF
echo "Email 2 sent: task (→ REQUEST HIGH)"
```
**Expected:** exit code 0

### Step 4 — Письмо 3: просьба написать ответ (ОТВЕТН → DRAFT)
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "partner@external.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: Re: Коммерческое предложение
From: partner@external.com
To: me@test.com

Нам нужно ответное письмо с подтверждением условий.
Подготовь черновик ответного письма пожалуйста.
EOF
echo "Email 3 sent: reply request (ОТВЕТН → DRAFT)"
```
**Expected:** exit code 0

### Step 5 — Все 3 письма в Maildev
```bash
sleep 1
curl -s http://localhost:1080/email | jq 'length'
```
**Expected:** 3

### Step 6 — Дождаться обработки всех трёх (до 90 сек)
```bash
SENDERS="'ci@jenkins.local','manager@company.ru','partner@external.com'"
echo "Waiting for all 3 emails..."
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(PGPASSWORD=mailagent_password psql \
    -h localhost -U mailagent_user -d leader_framework -t \
    -c "SELECT COUNT(*) FROM mailagent.processed_emails WHERE sender IN ($SENDERS);" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: processed=$COUNT/3"
  if [ "$COUNT" -ge 3 ] 2>/dev/null; then
    echo "  ✅ All 3 processed"
    break
  fi
done
```
**Expected:** count = 3

### Step 7 — Проверить agent_type для каждого письма
```bash
PGPASSWORD=mailagent_password psql \
  -h localhost -U mailagent_user -d leader_framework \
  -c "SELECT sender, subject, agent_type FROM mailagent.processed_emails WHERE sender IN ('ci@jenkins.local','manager@company.ru','partner@external.com') ORDER BY processed_at DESC;"
```
**Expected:**
- `ci@jenkins.local` → `NOISE`
- `manager@company.ru` → `REQUEST`
- `partner@external.com` → `DRAFT`

### Step 8 — CI письмо помечено прочитанным, остальные нет
```bash
curl -s http://localhost:1080/email | jq '[.[] | {from: .from[0].address, read}]'
```
**Expected:**
- `ci@jenkins.local` → `"read": true` (NOISE → markAsRead)
- `manager@company.ru` → `"read": false` (REQUEST → остаётся unread)
- `partner@external.com` → `"read": false` (DRAFT → остаётся unread)

### Step 9 — Лог: итог poll цикла
```bash
grep -i "poll finished\|processed.*REQUEST\|processed.*NOISE\|processed.*DRAFT" logs/JavaMailAgent.log | tail -5
```
**Expected:** строки с результатами обработки

## Cleanup
```bash
curl -s -X DELETE http://localhost:1080/email/all > /dev/null
echo "Cleanup done"
```
