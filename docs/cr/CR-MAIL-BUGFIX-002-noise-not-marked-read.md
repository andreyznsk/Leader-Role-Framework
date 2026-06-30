# CR-MAIL-BUGFIX-002: NOISE письма не помечаются read в Maildev после обработки

**Дата:** 2026-06-13
**Статус:** Draft
**Тип:** BugFix
**Сервис:** JavaMailAgent
**Источник:** `test-runner/reports/TEST-REPORT-2026-06-13.md`, сценарии `03_poll_cycle_noise.md`, `06_multiple_emails.md`

---

## Проблема

После того как JavaMailAgent классифицирует письмо как `NOISE` и вызывает `markAsRead`, письмо в Maildev REST API по-прежнему имеет `read=false`.

**Симптом:**
```bash
# После poll-цикла с NOISE письмом:
curl -s "http://172.80.2.1:18080/email" \
  | jq '[.[] | select(.from[0].address == "ci@jenkins.local")] | .[0].read'
# Ожидается: true
# Фактически: false
```

Лог сервиса при этом содержит: `Email {id} marked as read (NOISE)` — т.е. вызов выполняется без исключения.

---

## Локализация

### 1. `MailAgentJob.java` — вызов markAsRead (строки 124-127)

```java
if (resp.type() == AgentResponseType.NOISE) {
    mailClient.markAsRead(email.id(), email.folder());  // вызов есть
    log.info("Email {} marked as read (NOISE)", email.id());
}
```

Вызов присутствует и выполняется — об этом свидетельствует лог.

### 2. `MaildevClient.java` — реализация markAsRead (строки 90-101)

```java
@Override
public void markAsRead(String emailId, String folder) throws MailException {
    try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/email/" + emailId + "/read"))
            .method("PATCH", HttpRequest.BodyPublishers.noBody())
            .build();

        httpClient.send(request, HttpResponse.BodyHandlers.discarding());  // ← response игнорируется!
    } catch (Exception e) {
        throw new MailException("Failed to mark email " + emailId + " as read", e);
    }
}
```

**Два дефекта:**

**Дефект A — HTTP ответ игнорируется:**
`HttpResponse.BodyHandlers.discarding()` отбрасывает тело ответа, а статус-код не проверяется. Если Maildev возвращает 404 или 405 — ошибки не будет, лог напишет "marked as read", но реально ничего не изменится.

**Дефект B — Неверный HTTP метод:**
Maildev REST API не поддерживает `PATCH /email/{id}/read`. Корректный эндпоинт для пометки письма прочитанным в Maildev v2.x:
```
GET /email/:id/read
```
(нестандартно, но такова документация Maildev)

---

## Предлагаемый фикс

**Файл:** `JavaMailAgent/src/main/java/ru/andreyz/mailagent/client/MaildevClient.java`

```java
@Override
public void markAsRead(String emailId, String folder) throws MailException {
    try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/email/" + emailId + "/read"))
            .GET()  // ← был PATCH, нужен GET
            .build();

        HttpResponse<String> response = httpClient.send(
            request, HttpResponse.BodyHandlers.ofString());  // ← читать ответ

        if (response.statusCode() / 100 != 2) {
            log.warn("markAsRead returned HTTP {}: {}", response.statusCode(), response.body());
        }
    } catch (Exception e) {
        throw new MailException("Failed to mark email " + emailId + " as read", e);
    }
}
```

**Если `GET /email/{id}/read` тоже не работает** — проверить Maildev API по документации версии, используемой в `docker-compose.yml`. Альтернативный endpoint: `DELETE /email/{id}` (удаление = обработано).

---

## Scope

В scope:
- `JavaMailAgent/src/main/java/ru/andreyz/mailagent/client/MaildevClient.java` — метод `markAsRead`
- `JavaMailAgent/src/test/java/ru/andreyz/mailagent/client/MaildevClientTest.java` — добавить тест на HTTP статус-код

Не в scope:
- `EwsMailClient.java` — production клиент, отдельные правила
- `MailAgentJob.java` — вызов markAsRead стоит на правильном месте

---

## Acceptance Criteria

```bash
# 1. Отправить NOISE-письмо (с "BUILD" или "PASSED" в теме):
curl -s --max-time 10 --url "smtp://172.80.2.1:1025" \
  --mail-from "ci@jenkins.local" \
  --mail-rcpt "inbox@example.com" \
  --upload-file - <<'EOF'
From: ci@jenkins.local
To: inbox@example.com
Subject: Pipeline PASSED - Duration: 45s

Build #123 completed successfully.
EOF

# 2. Дождаться poll-цикла (до 90с)

# 3. Проверить read=true:
curl -s "http://172.80.2.1:18080/email" \
  | jq '[.[] | select(.from[0].address == "ci@jenkins.local")] | .[0].read'
# Ожидается: true

# 4. Прогнать сценарии:
# JavaMailAgent/test_e2e/03_poll_cycle_noise.md — Steps 4-5: PASS
# JavaMailAgent/test_e2e/06_multiple_emails.md — Step 8: PASS
```

---

## Приоритет

**LOW** — критично только для dev-окружения (Maildev). В production используется IMAP с флагом `\Seen`, который управляется через `EwsMailClient` или IMAP команды — отдельная реализация.

---

## Связанные CRs

- CR-BUGFIX-001 (BUG-002) — общий CR из прошлого прогона
