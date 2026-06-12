# TEST-REPORT-mail-run2

**Запуск:** 2026-06-11 19:45 — 19:55
**Профиль:** local
**Инициатор:** ручной запуск
**Env:** `source JavaMailAgent/test_e2e/env.sh`
**Сценарии:** 01_health_check, 02_maildev_send_receive

---

## Summary

| Сервис | Сборка | Запуск | Сценариев | PASS | FAIL | SKIP |
|--------|--------|--------|-----------|------|------|------|
| JavaMailAgent | ✅ | ✅ | 2 | — | — | — |
| **Итого шагов** | | | **12** | **11** | **1** | **0** |

| Сценарий | Итог |
|----------|------|
| 01_health_check | ✅ PASS (6/6) |
| 02_maildev_send_receive | ⚠️ PARTIAL (5/6) |

---

## Сценарий 01 — Health Check ✅ PASS

### Step 1 — Actuator health HTTP code ✅

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health
```
**Expected:** HTTP 200  
**Actual:** `200` ✅

---

### Step 2 — Статус UP ✅

```bash
curl -s http://localhost:8080/actuator/health
```
**Expected:** тело содержит `"status":"UP"`  
**Actual:** `{"status":"UP"}` ✅

---

### Step 3 — Maildev API доступен ✅

```bash
curl -s -o /dev/null -w "%{http_code}" $MAILDEV_URL
```
**Expected:** HTTP 200  
**Actual:** `200` ✅  
*(адрес из env.sh: `http://172.80.2.1:18080`)*

---

### Step 4 — Maildev возвращает JSON-массив ✅

```bash
curl -s $MAILDEV_URL/email
```
**Expected:** HTTP 200, тело начинается с `[`  
**Actual:** `[]` — HTTP 200, валидный JSON-массив ✅

---

### Step 5 — UI /ui/status доступен ✅

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ui/status
```
**Expected:** HTTP 200  
**Actual:** `200` ✅

---

### Step 6 — Лог содержит успешное подключение к Maildev ✅

```bash
grep -i "maildev connection\|Maildev.*OK\|connection OK" logs/JavaMailAgent.log | tail -3
```
**Expected:** строка содержит `OK` без `FAILED`  
**Actual:**
```
19:45:33 INFO  r.a.m.client.MaildevClient — ✅ Maildev connection OK — http://172.80.2.1:18080
```
✅

---

## Сценарий 02 — Maildev send/receive ⚠️ PARTIAL (5/6)

### Step 1 — Очистить ящик ✅

```bash
curl -s -X DELETE $MAILDEV_URL/email/all
```
**Actual:** `true` ✅

---

### Step 2 — Отправить письмо через SMTP ✅

```bash
curl -s --url "smtp://$MAILDEV_SMTP" \
  --mail-from "sender@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
MIME-Version: 1.0
Content-Type: text/plain; charset=UTF-8
Content-Transfer-Encoding: 8bit
Subject: E2E Test: простое письмо
...
EOF
```
**Expected:** exit code 0  
**Actual:** exit code 0 ✅  
*(MIME-заголовки добавлены для корректной передачи UTF-8)*

---

### Step 3 — Письмо появилось в Maildev ✅

**Expected:** length >= 1, subject содержит `E2E Test`, `read: false`  
**Actual:**
```
COUNT: 1
ID:      OJODWCwm
SUBJECT: E2E Test: простое письмо
FROM:    sender@test.com
READ:    False
```
✅ Все условия выполнены.

---

### Step 4 — Прочитать тело письма ✅

**Expected:** HTTP 200, поле `text` содержит `тестовое письмо`  
**Actual:**
```
subject: E2E Test: простое письмо
text:    Это тестовое письмо для E2E проверки Maildev.
contains тестовое: True
```
✅ Кириллица корректна (MIME charset=UTF-8 устранил double-encoding из run1).

---

### Step 5 — Пометить письмо прочитанным через PATCH ❌

```bash
curl -s -w "\n%{http_code}" -X PATCH "$MAILDEV_URL/email/$EMAIL_ID/read"
```
**Expected:** HTTP 200 или 204  
**Actual:** HTTP 404 — `Cannot PATCH /email/OJODWCwm/read`  

**Причина:** Maildev (`maildev/maildev:latest`) не реализует `PATCH /email/:id/read`.  
Письмо автоматически переходит в `read=true` при первом GET-запросе по ID (Step 4).  
**Не баг JavaMailAgent** — несоответствие API сценария и версии Maildev.

---

### Step 6 — Письмо read=true ✅

**Expected:** `true`  
**Actual:** `True` ✅  
*(помечено автоматически при GET в Step 4)*

---

### Cleanup ✅

```bash
curl -s -X DELETE $MAILDEV_URL/email/all
```
Inbox очищен.

---

## Сравнение run1 vs run2

| Шаг | run1 | run2 | Изменение |
|-----|------|------|-----------|
| 01/Step 1 actuator HTTP | ❌ 404 | ✅ 200 | Actuator добавлен |
| 01/Step 2 status:UP | ❌ 404 | ✅ UP | Actuator добавлен |
| 01/Step 3 Maildev доступен | ⚠️ wrong addr | ✅ env.sh | `$MAILDEV_URL` из env.sh |
| 02/Step 4 body charset | ⚠️ double-enc | ✅ корректно | MIME UTF-8 заголовки |
| 02/Step 5 PATCH read | ❌ 404 | ❌ 404 | Не поддерживается Maildev |

---

## Открытый дефект сценария

### CR-MAIL-SCENARIO-001 — PATCH /email/:id/read не поддерживается

**Файл:** `JavaMailAgent/test_e2e/02_maildev_send_receive.md`, Step 5  
**Проблема:** `PATCH /email/:id/read` → HTTP 404 в `maildev:latest`.  
**Поведение Maildev:** письмо автоматически помечается `read=true` при GET по ID.  
**Рекомендация:** заменить Step 5 на проверку, что GET-запрос возвращает `read=true` после Step 4, либо убрать Step 5 как избыточный (Step 6 уже покрывает это).

---

## Итог

| # | Сценарий | Шагов | PASS | FAIL |
|---|----------|-------|------|------|
| 01 | Health Check | 6 | 6 | 0 |
| 02 | Maildev send/receive | 6 | 5 | 1 |
| **Итого** | | **12** | **11** | **1** |

**JavaMailAgent работает корректно.**  
Единственный FAIL — ограничение API Maildev (PATCH read не реализован), не баг агента.  
По сравнению с run1: исправлено 4 из 5 проблем.
