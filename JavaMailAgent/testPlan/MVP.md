# E2E Test Plan: JavaMailAgent (local profile, Maildev)

## Окружение

```bash
# 1. Инфра
docker compose --profile local up -d
# Maildev UI: http://localhost:1080
# SMTP:       localhost:1025
# memory-service должен быть запущен на :8082 (или memory.service.enabled=false)

# 2. Рабочая директория — корень Leader-Role-Framework
# Создать директории если их нет:
mkdir -p mail/inbox mail/processed mail/drafts plans logs

# 3. Запуск агента
java -jar JavaMailAgent/target/mail-agent-1.0.0.jar \
  --spring.profiles.active=local

# 4. Проверить старт
curl -s http://localhost:8080/ui/status
```

---

## Сценарии

### TC-01 — REQUEST с HIGH приоритетом

**Цель:** письмо с дедлайном → задача в план + PENDING в memory-service

```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "ivanov@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: Ревью PR #42 — важно до завтра
From: ivanov@company.ru
To: me@test.com

Привет, нужен ревью PR #42. Дедлайн завтра утром.
EOF
```

**Ожидаемый результат (через ≤60 сек):**
- [ ] Maildev помечает письмо как прочитанное (`GET http://localhost:1080/email` → `"read": true`)
- [ ] Файл появился в `mail/processed/`, исчез из `mail/inbox/`
- [ ] `plans/today.md` содержит строку с PR #42 и приоритетом `[P1]` или `HIGH`
- [ ] `POST /api/tasks/pending` отправлен на :8082 с `priority: HIGH`
- [ ] В логах: `Classified as REQUEST, priority HIGH` + `Pending task created`
- [ ] `/ui/status` показывает: mail/inbox=0, mail/processed=1

---

### TC-02 — NOISE (CI-уведомление)

```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "ci@jenkins.local" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: Build #321 passed
From: ci@jenkins.local
To: me@test.com

Pipeline main: SUCCESS. Duration: 4m32s.
EOF
```

**Ожидаемый результат:**
- [ ] Письмо помечено прочитанным
- [ ] Файл перемещён в `mail/processed/`
- [ ] `plans/today.md` **НЕ изменился**
- [ ] memory-service **НЕ вызывался** (или вызов отсутствует в логах)
- [ ] В логах: `NOISE` + имя класса `ActionExecutor`

---

### TC-03 — CRITICAL приоритет

```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "cto@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: P1 инцидент — payments упал
From: cto@company.ru
To: me@test.com

Срочно! P1 инцидент. Payments сервис недоступен с 10:00. ASAP.
EOF
```

**Ожидаемый результат:**
- [ ] `priority: CRITICAL` в запросе к memory-service
- [ ] `plans/today.md` содержит запись

---

### TC-04 — DRAFT сценарий

```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "hr@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: Нужен ответ на оффер для Петрова
From: hr@company.ru
To: me@test.com

Подготовь, пожалуйста, ответное письмо по оферу для Петрова.
EOF
```

**Ожидаемый результат:**
- [ ] Тип `DRAFT` в логах (`ActionExecutor`)
- [ ] Файл появился в `mail/drafts/`
- [ ] memory-service **НЕ вызывался**

---

### TC-05 — memory-service недоступен (graceful degradation)

```bash
# Остановить memory-service (или выставить неверный порт)
# Затем отправить REQUEST-письмо (TC-01)
```

**Ожидаемый результат:**
- [ ] Агент **не падает** и не останавливает обработку
- [ ] Письмо всё равно перемещается в `mail/processed/`
- [ ] `plans/today.md` всё равно обновляется
- [ ] В логах: `WARN Failed to reach memory-service` (без стек-трейса уровня ERROR)
- [ ] Следующий poll запускается штатно (fixedDelay не сломан)

---

### TC-06 — batch: несколько писем за один poll

```bash
# Отправить 3 письма подряд до следующего тика
for i in 1 2 3; do
  curl -s --url "smtp://localhost:1025" \
    --mail-from "user$i@test.com" \
    --mail-rcpt "me@test.com" \
    --upload-file - <<EOF
Subject: Задача $i
From: user$i@test.com
To: me@test.com

Нужно сделать задачу $i до конца недели.
EOF
done
```

**Ожидаемый результат:**
- [ ] Все 3 письма обработаны за один poll
- [ ] Лог: `Poll finished: 3 processed`
- [ ] `/ui/status` inbox=0, processed=3

---

### TC-07 — Web UI

```bash
curl -s http://localhost:8080/ui/status
```

**Ожидаемый результат:**
- [ ] HTTP 200
- [ ] Показывает inbox/processed/drafts счётчики (не нули если были письма)
- [ ] Показывает последние строки лога
- [ ] Статус подключения к memory-service отображается корректно

---

## Чеклист запуска

| Шаг | Команда | Ожидание |
|-----|---------|----------|
| Инфра | `docker compose --profile local up -d` | maildev:1080 отвечает |
| Сборка | `cd JavaMailAgent && mvn package -q` | `BUILD SUCCESS` |
| Старт | `java -jar ... --spring.profiles.active=local` | `Started MailAgentApplication` в логах |
| Smoke | `curl localhost:8080/ui/status` | HTTP 200 |

---

## Что проверять в Maildev UI

`http://localhost:1080` — после каждого теста:
- вкладка **All Mail**: письмо должно быть помечено как прочитанное
- REST API: `GET http://localhost:1080/email` → поле `"read": true`
