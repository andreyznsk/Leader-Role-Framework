# CR-MAIL-003: Новый тип CAPTURE — письмо как заметка

**Дата:** 2026-06-12
**Статус:** Implemented
**Сервис:** MAIL (JavaMailAgent)
**Зависимости:** CR-MEM-004 (Capture Bot должен быть реализован в JavaMemoryService)

---

## Проблема / Мотивация

Текущая классификация JavaMailAgent знает три типа:
- `REQUEST` — нужно действие → задача в плане
- `DRAFT` — нужен черновик ответа
- `NOISE` — автоматика, игнорировать

Между REQUEST и NOISE существует серая зона: письма с полезной информацией,
которые не требуют немедленного действия, но содержат знание, которое стоит сохранить.

**Примеры:**
- "FYI: мы переехали на новый кластер с 1 июля"
- "Напоминание: в пятницу плановые работы на прод"
- "Аналитика: метрики за май — конверсия упала на 3%"
- "Архитектурное решение: команда платежей переходит на event sourcing"
- "К сведению: Иванов переходит в другую команду"

Сейчас агент вынужден классифицировать их как NOISE (теряем информацию)
или REQUEST (создаём мусорные задачи в плане).

**Решение:** добавить тип `CAPTURE` — письмо попадает в `POST /api/capture`
в JavaMemoryService, где вечером CaptureScheduler классифицирует его вместе
с остальными заметками дня.

---

## Решение

### Изменение 1 — `AgentResponseType.java`

```java
// было:
public enum AgentResponseType {
    DRAFT, REQUEST, NOISE
}

// стало:
public enum AgentResponseType {
    DRAFT, REQUEST, NOISE, CAPTURE
}
```

---

### Изменение 2 — `AgentResponse.java`

Добавить поле `captureText` — краткое изложение сути письма для сохранения в capture-inbox.

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(
    AgentResponseType type,
    String emailId,
    String note,
    String taskLine,
    String taskTitle,
    String priority,
    String sender,
    String draftPath,
    String captureText   // ← новое поле, только для CAPTURE
) {}
```

---

### Изменение 3 — `PromptBuilder.java`

Добавить тип CAPTURE в инструкцию агенту и поле в JSON-шаблон:

```java
public String build(Email email) {
    return """
        Ты — ассистент Tech Lead. Проанализируй входящее письмо и верни JSON.

        Письмо:
        От: %s
        Тема: %s
        Текст:
        %s

        Верни JSON строго в следующем формате (только JSON, без пояснений):
        {
          "type": "<REQUEST|DRAFT|NOISE|CAPTURE>",
          "emailId": "%s",
          "note": "<краткое объяснение решения>",
          "taskLine": "<строка для plans/today.md, только для REQUEST, иначе null>",
          "taskTitle": "<заголовок задачи, только для REQUEST, иначе null>",
          "priority": "<LOW|NORMAL|HIGH|CRITICAL, только для REQUEST, иначе null>",
          "sender": "<email отправителя, только для REQUEST, иначе null>",
          "draftPath": "<путь к черновику drafts/..., только для DRAFT, иначе null>",
          "captureText": "<суть письма 1-2 предложения, только для CAPTURE, иначе null>"
        }

        Типы:
        - REQUEST: письмо требует конкретного действия от Tech Lead
        - DRAFT:   требует ответного письма, нужен черновик
        - NOISE:   CI/CD уведомление, реклама, автоматика — никакой пользы
        - CAPTURE: письмо содержит полезную информацию/знание, но без срочного действия.
                   Примеры: FYI-рассылки, архитектурные решения коллег, аналитика,
                   напоминания о плановых работах, новости команды.
                   captureText = краткое изложение сути в 1-2 предложения.

        Приоритет (только для REQUEST):
        - CRITICAL: "срочно", "asap", "до сегодня"
        - HIGH:     "до завтра", "важно", P1/P2 инцидент
        - NORMAL:   конкретный дедлайн на этой неделе
        - LOW:      без дедлайна или "когда будет время"

        taskLine формат: "- [ ] [PRIORITY] Описание — от sender@example.com"
        """.formatted(email.from(), email.subject(), email.body(), email.id());
}
```

---

### Изменение 4 — `MemoryServiceClient.java`

Добавить метод `createCapture`:

```java
public void createCapture(String text, String source) {
    if (!enabled) {
        log.debug("memory-service disabled, skipping capture");
        return;
    }
    try {
        String body = objectMapper.writeValueAsString(
            Map.of("text", text, "source", source)
        );
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/capture"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(5))
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.info("Capture saved to memory-service");
        } else {
            log.warn("memory-service /api/capture returned {}: {}",
                response.statusCode(), response.body());
        }
    } catch (Exception e) {
        log.warn("Failed to save capture to memory-service: {}", e.getMessage());
    }
}
```

Импорты: `java.util.Map` — уже есть в проекте через Jackson.

---

### Изменение 5 — `ActionExecutor.java`

Добавить ветку `CAPTURE` в switch:

```java
case CAPTURE -> {
    String text = response.captureText() != null && !response.captureText().isBlank()
        ? response.captureText()
        : response.note();
    memoryServiceClient.createCapture(text, "email");
    moveToProcessed(inbox, processed);
    log.info("CAPTURE → memory-service: {}", text);
}
```

**Поведение `markAsRead`:** для `CAPTURE` НЕ вызывается — письмо остаётся
непрочитанным в почте. Аналогично REQUEST и DRAFT.
Дедупликация через `processed_emails` предотвращает повторную обработку.

---

### Изменение 6 — `MockClaudeRunner.java`

Добавить распознавание CAPTURE по ключевым словам для E2E тестов:

```java
// В метод detectType(), перед return REQUEST:
if (upper.contains("FYI") || upper.contains("К СВЕДЕНИЮ") ||
    upper.contains("ИНФО:") || upper.contains("НАПОМИНАНИЕ:") ||
    upper.contains("CAPTURE"))
    return AgentResponseType.CAPTURE;
```

В метод `run()` добавить ветку в switch:

```java
case CAPTURE -> new AgentResponse(
    type,
    emailId,
    "Mock: classified as CAPTURE by keyword",
    null, null, null, null, null,
    "Mock capture: " + extractEmailSection(prompt).lines()
        .filter(l -> !l.isBlank()).findFirst().orElse("(no text)")
);
```

---

## Изменения в схеме БД

Нет. CAPTURE использует существующий `POST /api/capture` в JavaMemoryService.

---

## Изменения в конфигурации

Нет. `markAsRead` для CAPTURE не вызывается — это уже заложено в логике
`MailAgentJob` (вызов только для NOISE).

---

## Поведение по типам после CR

| Тип | markAsRead | plans/today.md | memory-service | Остаётся unread |
|-----|-----------|----------------|----------------|-----------------|
| REQUEST | ❌ | ✅ строка | POST /api/tasks/pending | ✅ |
| DRAFT | ❌ | ❌ | ❌ | ✅ |
| NOISE | ✅ | ❌ | ❌ | ❌ |
| CAPTURE | ❌ | ❌ | POST /api/capture | ✅ |

---

## Как тестировать

```bash
# 1. Запустить с memory.service.enabled=true, mock.agent=true
# 2. Отправить FYI письмо
curl -s --url "smtp://localhost:1025" \
  --mail-from "team@company.ru" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: FYI: переезд на новый кластер
From: team@company.ru
To: me@test.com

К сведению: с 1 июля переезжаем на новый Kubernetes кластер.
Никаких действий не требуется, просто будьте в курсе.
EOF

# 3. Дождаться poll цикла (до 90 сек)
# 4. Проверить capture в MemoryService
curl -s http://localhost:8082/api/capture/today | jq '[.[] | select(.source == "email")]'

# 5. Проверить что задача НЕ создана в PENDING
curl -s http://localhost:8082/api/tasks/pending | jq 'length'

# 6. Запустить классификацию capture
curl -s -X POST http://localhost:8082/api/capture/process-now

# 7. Проверить результат классификации
curl -s http://localhost:8082/api/notes | jq '.[0]'
```

---

## Порядок реализации

1. `AgentResponseType.java` — добавить CAPTURE
2. `AgentResponse.java` — добавить поле captureText
3. `PromptBuilder.java` — расширить инструкцию
4. `MemoryServiceClient.java` — добавить createCapture()
5. `ActionExecutor.java` — добавить case CAPTURE
6. `MockClaudeRunner.java` — добавить keyword + ветку в switch
7. Запустить существующие E2E тесты — убедиться что NOISE/REQUEST не сломались
8. Прогнать `08_integration_capture_type.md`
