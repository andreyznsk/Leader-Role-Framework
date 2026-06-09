# CR-001: Mock ClaudeRunner + Mail Connection Check on Startup

**Дата:** 2026-06-06  
**Статус:** Draft  
**Сервис:** JavaMailAgent  
**Зависимости:** нет

---

## Проблема / Мотивация

1. При локальной разработке и тестировании нежелательно запускать реальный
   Claude-процесс на каждое письмо — дорого, медленно, требует наличия `claude` CLI.
   Нужен мок-бин `ClaudeRunner` который эмулирует ответ агента на основе содержимого письма.

2. При старте приложения неизвестно — удалось ли подключиться к почтовому серверу.
   Ошибка обнаруживается только в первом цикле поллинга. Нужна явная проверка
   коннекта при создании бина с понятным логом.

---

## Решение

### 1. Mock ClaudeRunner

Новый класс `MockClaudeRunner` активируется через `@ConditionalOnProperty`.  
При активации выводит `WARN` в лог.  
Парсит тело письма из промпта и возвращает `AgentResponse` без запуска процесса.

### 2. Connection Check в MailClient

При создании бина (`@PostConstruct`) каждый клиент (`EwsMailClient`,
`ImapMailClient`, `MaildevClient`) проверяет соединение и пишет в лог
`INFO` при успехе или `ERROR` при ошибке.

---

## Изменения в конфигурации

### application.properties — добавить
```properties
# Mock agent — true для локальной разработки без Claude CLI
mock.agent=false
```

### application-local.properties.example — добавить
```properties
mock.agent=true
```

---

## Новые классы

### MockClaudeRunner.java

```java
@Component
@ConditionalOnProperty(name = "mock.agent", havingValue = "true")
public class MockClaudeRunner implements ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(MockClaudeRunner.class);

    @PostConstruct
    public void init() {
        log.warn("⚠️  MOCK ClaudeRunner is active — real Claude agent will NOT be called");
        log.warn("⚠️  Set mock.agent=false to use real Claude agent");
    }

    @Override
    public AgentResponse run(String prompt) {
        AgentResponseType type     = detectType(prompt);
        String priority            = detectPriority(prompt);
        String emailId             = extractEmailId(prompt);

        log.debug("MockClaudeRunner: detected type={}, priority={}", type, priority);

        return switch (type) {
            case REQUEST -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as REQUEST by keyword",
                "- [ ] [" + priorityLabel(priority) + "] Mock task from email " + emailId,
                "Mock task title",
                priority,
                extractSender(prompt),
                null
            );
            case DRAFT -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as DRAFT by keyword",
                null, null, null, null,
                "drafts/" + emailId + "-draft.md"
            );
            case NOISE -> new AgentResponse(
                type,
                emailId,
                "Mock: classified as NOISE (default or keyword)",
                null, null, null, null, null
            );
        };
    }

    // Ищем первый встреченный идентификатор в теле письма.
    // По умолчанию — NOISE.
    private AgentResponseType detectType(String prompt) {
        String upper = prompt.toUpperCase();
        if (upper.contains("REQUEST")) return AgentResponseType.REQUEST;
        if (upper.contains("DRAFT"))   return AgentResponseType.DRAFT;
        if (upper.contains("NOISE"))   return AgentResponseType.NOISE;
        return AgentResponseType.NOISE;  // default
    }

    // Ищем приоритет в теле письма.
    // По умолчанию — NORMAL.
    private String detectPriority(String prompt) {
        String upper = prompt.toUpperCase();
        if (upper.contains("CRITICAL")) return "CRITICAL";
        if (upper.contains("HIGH"))     return "HIGH";
        if (upper.contains("LOW"))      return "LOW";
        if (upper.contains("NORMAL"))   return "NORMAL";
        return "NORMAL";  // default
    }

    // P0/P1/P2/P3 для строки задачи в плане
    private String priorityLabel(String priority) {
        return switch (priority) {
            case "CRITICAL" -> "P0";
            case "HIGH"     -> "P1";
            case "LOW"      -> "P3";
            default         -> "P2";  // NORMAL
        };
    }

    private String extractEmailId(String prompt) {
        // Ожидаем в промпте строку вида: emailId: AAMk-xxx
        return extractField(prompt, "emailId", "mock-id-" + System.currentTimeMillis());
    }

    private String extractSender(String prompt) {
        return extractField(prompt, "От", "unknown@mock.local");
    }

    private String extractField(String prompt, String field, String defaultValue) {
        for (String line : prompt.lines().toList()) {
            if (line.toLowerCase().startsWith(field.toLowerCase())) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) return parts[1].trim();
            }
        }
        return defaultValue;
    }
}
```

---

### Изменения в реальном ClaudeRunner

Добавить `@ConditionalOnProperty` чтобы не конфликтовал с моком:

```java
@Component
@ConditionalOnProperty(name = "mock.agent", havingValue = "false", matchIfMissing = true)
public class ClaudeRunner {
    // существующий код без изменений
}
```

---

## Connection Check в MailClient бинах

Добавить `@PostConstruct` метод в каждый клиент.

### EwsMailClient.java — добавить
```java
@PostConstruct
public void checkConnection() {
    try {
        // Пробуем получить 1 письмо — минимальный запрос к серверу
        service.findItems(
            WellKnownFolderName.Inbox,
            new ItemView(1)
        );
        log.info("✅ EWS connection OK — {}", config.getEwsUrl());
    } catch (Exception e) {
        log.error("❌ EWS connection FAILED — {}: {}", config.getEwsUrl(), e.getMessage());
        // Не бросаем исключение — приложение стартует,
        // ошибка будет повторяться в каждом цикле поллинга
    }
}
```

### ImapMailClient.java — добавить
```java
@PostConstruct
public void checkConnection() {
    try {
        store.connect(config.getImapHost(), config.getMailUsername(), password);
        log.info("✅ IMAP connection OK — {}:{}", config.getImapHost(), config.getImapPort());
    } catch (MessagingException e) {
        log.error("❌ IMAP connection FAILED — {}:{}: {}",
            config.getImapHost(), config.getImapPort(), e.getMessage());
    }
}
```

### MaildevClient.java — добавить
```java
@PostConstruct
public void checkConnection() {
    try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getMaildevApiUrl() + "/email"))
            .timeout(Duration.ofSeconds(3))
            .GET()
            .build();
        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            log.info("✅ Maildev connection OK — {}", config.getMaildevApiUrl());
        } else {
            log.error("❌ Maildev returned HTTP {}", response.statusCode());
        }
    } catch (Exception e) {
        log.error("❌ Maildev connection FAILED — {}: {}",
            config.getMaildevApiUrl(), e.getMessage());
    }
}
```

---

## Ожидаемый вывод при старте

### С моком (local + mock.agent=true)
```
WARN  MockClaudeRunner - ⚠️  MOCK ClaudeRunner is active — real Claude agent will NOT be called
WARN  MockClaudeRunner - ⚠️  Set mock.agent=false to use real Claude agent
INFO  MaildevClient    - ✅ Maildev connection OK — http://localhost:1080
```

### Без мока, коннект успешен (prod)
```
INFO  EwsMailClient    - ✅ EWS connection OK — https://mail.company.com/EWS/Exchange.asmx
```

### Без мока, коннект упал (prod)
```
ERROR EwsMailClient    - ❌ EWS connection FAILED — https://mail.company.com/EWS/Exchange.asmx: Connection refused
```

---

## Изменения в структуре проекта

```
src/main/java/ru/andreyz/mailagent/
└── scheduler/
    ├── ClaudeRunner.java          ← добавить @ConditionalOnProperty
    └── MockClaudeRunner.java      ← новый класс
```

---

## Как тестировать

```bash
# 1. Поднять Maildev
docker compose up -d

# 2. Запустить с моком
SPRING_PROFILES_ACTIVE=local \
  java -jar target/mail-agent-1.0.0.jar

# 3. Убедиться в логах что мок активен и Maildev доступен

# 4. Отправить тестовое письмо с текстом "REQUEST HIGH"
curl -s --url "smtp://localhost:1025" \
  --mail-from "test@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: Test
From: test@test.com
To: me@test.com

REQUEST HIGH пожалуйста сделай ревью
EOF

# 5. Проверить что задача появилась в plans/today.md с приоритетом P1
```

---

## Что НЕ меняется

- Реальный `ClaudeRunner` — только добавляется аннотация, логика не трогается
- Контракт `AgentResponse` — мок возвращает ту же структуру
- `ActionExecutor` — не знает про мок, работает с интерфейсом
