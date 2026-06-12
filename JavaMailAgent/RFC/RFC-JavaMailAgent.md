# RFC: JavaMailAgent — Java Application Core

**Статус:** Living Document  
**Дата:** 2026-06-12  
**Проект:** Leader-Role-Framework / JavaMailAgent  
**Запускать Claude Code из:** `Leader-Role-Framework/JavaMailAgent/`

---

## Контекст системы
Перед работой с интеграциями читай `ARCHITECTURE.md`.

## 1. Обзор

Spring Boot 3 приложение. Подключается к корпоративному почтовому серверу,
читает новые письма, для каждого запускает Claude-агента, выполняет
детерминированное действие по результату. Интегрируется с `java-memory-service`
для хранения задач. Имеет минимальный Web UI для просмотра статуса.

Работает как бесконечный фоновый процесс.

---

## 2. Стек и зависимости

| Что | Версия | Зачем |
|-----|--------|-------|
| Java | 21 | Records, sealed classes, switch expressions |
| Spring Boot | 3.3 | Scheduling, Web UI, конфиг, логи |
| Spring Web | 3.3 | Thymeleaf UI + REST endpoints |
| Spring Scheduling | 3.3 | `@Scheduled` вместо ScheduledExecutorService |
| Thymeleaf | 3.1 | Шаблоны для Web UI |
| EWS Java API | 2.0 | Microsoft Exchange on-premise |
| Jakarta Mail | 2.0.1 | IMAP |
| Jackson Databind | 2.17 | JSON: AgentResponse, Email → файл |
| Logback | 1.5 | Структурированные логи в файл с ротацией (через Spring Boot) |
| Spring Data JDBC | 3.3 | ORM для таблицы `processed_emails` (dedup) |
| Flyway | 10 | Миграции схемы `mailagent` |
| PostgreSQL JDBC | 42 | Драйвер БД |
| maven-shade-plugin | 3.5 | Fat-jar |

**БД** — mail-agent хранит обработанные письма в собственной схеме `mailagent`
(таблица `processed_emails`) для дедупликации между перезапусками.
Задачи пользователя — по-прежнему в `java-memory-service`.

---

## 3. Окружения и конфиги

### Концепция

Spring Boot поддерживает профили через `application-{ENV}.yml`.
Файлы в `src/main/resources/`. Профиль выбирается через `--spring.profiles.active`
или `SPRING_PROFILES_ACTIVE`.

```
src/main/resources/
├── application.yml              ← общие настройки (в git)
├── application-local.yml        ← Maildev Docker
└── application-prod.yml         ← Exchange/EWS placeholders

корень проекта (примеры):
├── application-local.yml.example
├── application-dev.yml.example
└── application-prod.yml.example
```

В git не хранить реальные пароли. Для prod используются env placeholders.

### application.yml — общие настройки (в git)
```yaml
spring:
  application:
    name: mail-agent
  datasource:
    url: jdbc:postgresql://localhost:5432/leader_framework
    username: mailagent_user
    password: ${POSTGRES_MAILAGENT_PASSWORD:mailagent_password}
  flyway:
    schemas: mailagent
    default-schema: mailagent
    locations: classpath:db/migration

management:
  endpoints:
    web:
      exposure:
        include: health,info,mappings

mail:
  protocol: maildev
  poll-interval-seconds: 60
  fetch-limit: 20
  folders:
    exclude:
      - Sent
      - Drafts
      - Trash
      - Spam
      - Archive
      - Junk
      - Deleted Items

agent:
  timeout-minutes: 5

path:
  inbox: mail/inbox
  processed: mail/processed
  drafts: mail/drafts
  plan: plans/today.md

memory:
  service:
    url: http://localhost:8082
    enabled: true

mock:
  agent: false

logging:
  file:
    name: logs/mail-agent.log
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 30
  pattern:
    file: "%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n"
    console: "%d{HH:mm:ss} %-5level %logger{36} - %msg%n"
```

### application-local.yml — Maildev (локально, НЕ в git)
```yaml
# Maildev запускается в Docker (JavaMailAgent/docker-compose.yml)
# Порты: 18080:1080 (Web UI + HTTP API), 1025:1025 (SMTP)
# IP 172.80.2.1 — Docker bridge, доступен с хоста
spring:
  datasource:
    url: "jdbc:postgresql://172.80.2.1:5432/leader_framework?sslmode=disable"
    username: mailagent_user
    password: mailagent_password

mail:
  protocol: maildev
  poll-interval-seconds: 30

maildev:
  api-url: http://172.80.2.1:18080

memory:
  service:
    enabled: false

mock:
  agent: true
```

### application-dev.yml.example — IMAP стенд
```yaml
mail:
  protocol: imap
  username: user@company.com
  password:

imap:
  host: mail.dev.company.com
  port: 993
  ssl: true
  folder: INBOX
```

### application-prod.yml — Exchange
```yaml
spring:
  datasource:
    url: ${MAILAGENT_DB_URL:jdbc:postgresql://localhost:5432/leader_framework}
    username: ${MAILAGENT_DB_USER:mailagent_user}
    password: ${MAILAGENT_DB_PASSWORD}

mail:
  protocol: ews
  username: ${MAIL_USERNAME}
  password: ${MAIL_PASSWORD}
  poll-interval-seconds: ${MAIL_POLL_INTERVAL_SECONDS:60}
  fetch-limit: ${MAIL_FETCH_LIMIT:20}
  folders:
    exclude:
      - Sent
      - Drafts
      - Trash
      - Spam
      - Archive
      - Junk
      - Deleted Items
      - Inbox/CI
      - Inbox/CI/CD
      - Inbox/Jenkins
      - Inbox/GitLab

ews:
  url: ${EWS_URL:}
  autodiscover: ${EWS_AUTODISCOVER:false}
  domain: ${EWS_DOMAIN:}
  version: ${EWS_VERSION:Exchange2010_SP2}
  timeout-seconds: ${EWS_TIMEOUT_SECONDS:30}

memory:
  service:
    url: ${MEMORY_SERVICE_URL:http://localhost:8082}
    enabled: ${MEMORY_SERVICE_ENABLED:true}
```

`mail.folders.exclude` принимает имя папки (`Junk`) или полный путь от Inbox
(`Inbox/CI/CD`). Если исключена родительская папка, её подпапки также не сканируются.

---

## 4. Docker Compose — локальное тестирование

Два файла:
- `Leader-Role-Framework/docker-compose.yml` — PostgreSQL + OpenSearch (общая инфраструктура)
- `JavaMailAgent/docker-compose.yml` — Maildev (только для mail-agent)

```bash
# Общая инфраструктура (PostgreSQL + OpenSearch)
docker compose up -d

# Maildev для local-профиля (из JavaMailAgent/)
cd JavaMailAgent && docker compose up -d
# Web UI: http://localhost:18080
```

Порты Maildev: `18080` → Web UI + HTTP API, `1025` → SMTP.

Postgres поднимается с БД `leader_framework`, суперюзер `superuser`.
При первом старте `infra/postgres/init.sql` создаёт изолированные схемы
и пользователей для каждого сервиса.

```bash
# Отправить тестовое письмо в Maildev
curl -s --url "smtp://localhost:1025" \
  --mail-from "sender@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: Нужен ревью PR #42
From: sender@test.com
To: me@test.com

Привет, можешь посмотреть PR #42? Дедлайн сегодня.
EOF
```

---

## 5. Структура проекта

```
JavaMailAgent/
├── CLAUDE.md
├── ARCHITECTURE.md               ← симлинк на ../ARCHITECTURE.md
├── RFC/
│   └── RFC-JavaMailAgent.md      ← этот файл
├── cr/
│   ├── CR-001-mock-agent-and-connection-check.md
│   └── CR-002-processed-emails-tracking.md
├── docker-compose.yml            ← Maildev (18080:1080, 1025:1025)
├── application-local.yml.example
├── application-dev.yml.example
├── application-prod.yml.example
├── pom.xml
└── src/
    ├── main/
    │   ├── java/ru/andreyz/mailagent/
    │   │   ├── MailAgentApplication.java       ← @SpringBootApplication + @EnableScheduling
    │   │   ├── config/
    │   │   │   └── MailConfig.java             ← @ConfigurationProperties (вложенные static классы)
    │   │   ├── client/
    │   │   │   ├── MailClient.java             ← интерфейс
    │   │   │   ├── MailException.java          ← checked exception для MailClient
    │   │   │   ├── MaildevClient.java          ← HTTP API (local) ✅ реализован
    │   │   │   ├── EwsMailClient.java          ← Exchange/EWS (prod) ✅ реализован
    │   │   │   ├── ImapMailClient.java         ← IMAP (dev) 🔜 planned
    │   │   ├── model/
    │   │   │   ├── Email.java                  ← record (id, subject, from, body, receivedAt, folder)
    │   │   │   ├── AgentResponse.java          ← record (@JsonInclude NON_NULL)
    │   │   │   ├── AgentResponseType.java      ← enum: REQUEST/DRAFT/NOISE/CAPTURE
    │   │   │   ├── PendingTaskRequest.java     ← record, payload для memory-service
    │   │   │   └── ProcessedEmail.java         ← Spring Data JDBC record (@Table mailagent.processed_emails)
    │   │   ├── repository/
    │   │   │   └── ProcessedEmailRepository.java ← CrudRepository, existsByEmailId()
    │   │   ├── scheduler/
    │   │   │   ├── MailAgentJob.java           ← @Scheduled(fixedDelay), главный цикл
    │   │   │   ├── PromptBuilder.java          ← формирует промпт для Claude
    │   │   │   ├── ClaudeRunner.java           ← интерфейс (run prompt → AgentResponse)
    │   │   │   ├── ClaudeRunnerImpl.java       ← запуск claude --print (@ConditionalOnProperty mock.agent=false)
    │   │   │   ├── MockClaudeRunner.java       ← мок (@ConditionalOnProperty mock.agent=true)
    │   │   │   └── ActionExecutor.java         ← switch по AgentResponseType
    │   │   ├── integration/
    │   │   │   └── MemoryServiceClient.java    ← POST /api/tasks/pending + isHealthy()
    │   │   └── web/
    │   │       └── StatusController.java       ← GET /ui/status (Thymeleaf)
    │   └── resources/
    │       ├── application.yml                 ← общие настройки (в git)
    │       ├── application-local.yml           ← Maildev Docker (НЕ в git)
    │       ├── logback-spring.xml              ← ротация логов по профилям
    │       ├── db/migration/
    │       │   └── V1__create_processed_emails.sql
    │       └── templates/
    │           └── status.html                 ← Thymeleaf шаблон
    └── test/java/ru/andreyz/mailagent/
        ├── client/MaildevClientTest.java
        ├── scheduler/ActionExecutorTest.java
        └── integration/MemoryServiceClientTest.java
```

---

## 6. Логи

### Структура

```
logs/
├── mail-agent.log                  ← текущий лог
├── mail-agent.2026-06-06.log       ← архив по дням
└── mail-agent.2026-06-05.log
```

Ротация: по размеру 10MB и по дате. Хранить 30 дней.

### Что логируем

```
10:32:10 INFO  MailAgentJob        - Poll started — scanning 2 folder(s): [INBOX, Work]
10:32:11 INFO  MailAgentJob        - Folder [INBOX]: 3 unread email(s)
10:32:11 INFO  MailAgentJob        - Processing email AAMk-123 from ivanov@company.ru: "Обсудить архитектуру payments" [INBOX]
10:32:14 INFO  MailAgentJob        - Classified as REQUEST, priority HIGH
10:32:14 INFO  MemoryServiceClient - Pending task created, id=42
10:32:14 INFO  ActionExecutor      - REQUEST → plan + memory-service: "- [ ] [HIGH] Обсудить архитектуру payments — от ivanov@company.ru"
10:32:15 INFO  MailAgentJob        - Processing email AAMk-456 from ci@jenkins.local: "Build #321 passed" [INBOX]
10:32:17 INFO  MailAgentJob        - Classified as NOISE
10:32:17 INFO  ActionExecutor      - NOISE: CI уведомление, пропускаем
10:32:17 INFO  MailAgentJob        - Email AAMk-456 marked as read (NOISE)
10:32:18 INFO  MailAgentJob        - Poll finished: 2 processed (1 REQUEST, 0 DRAFT, 1 NOISE, 0 CAPTURE, 0 errors)
10:32:18 WARN  MailAgentJob        - Email AAMk-789 failed: Claude timed out after 5 minutes, will retry next poll
```

### logback-spring.xml
```xml
<configuration>
    <springProfile name="local,dev">
        <!-- В консоль — подробно -->
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss} %-5level %-30logger{30} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO"><appender-ref ref="CONSOLE"/></root>
    </springProfile>

    <springProfile name="prod">
        <!-- В файл с ротацией -->
        <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
            <file>logs/mail-agent.log</file>
            <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                <fileNamePattern>logs/mail-agent.%d{yyyy-MM-dd}.log</fileNamePattern>
                <maxHistory>30</maxHistory>
            </rollingPolicy>
            <encoder>
                <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %-30logger{30} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO"><appender-ref ref="FILE"/></root>
    </springProfile>
</configuration>
```

---

## 7. Scheduler

`fixedDelay` — следующий тик только после завершения предыдущего (один поток).

Логика поллинга:
1. Получить список папок (`listFolders`), исключить `mail.folders.exclude`
2. Для каждой папки — получить непрочитанные письма (`listUnread(folder, limit)`)
3. Пропустить письма, уже записанные в `processed_emails` (dedup по `emailId`)
4. Обработать: Claude → ActionExecutor → записать в `processed_emails`
5. `markAsRead` — **только для NOISE** (`REQUEST`/`DRAFT`/`CAPTURE` остаются непрочитанными)

```java
// fixedDelay — следующий тик только после завершения предыдущего
@Scheduled(fixedDelayString = "${mail.poll-interval-seconds:60}000")
public void poll() {
    List<String> folders = mailClient.listFolders(folderProperties.getExclude());

    for (String folder : folders) {
        List<Email> emails = mailClient.listUnread(folder, mailProperties.getFetchLimit());
        for (Email email : emails) {
            if (processedEmailRepository.existsByEmailId(email.id())) continue;
            AgentResponse resp = processEmail(email);
            processedEmailRepository.save(ProcessedEmail.of(email, resp.type().name()));
        }
    }
}

private AgentResponse processEmail(Email email) throws Exception {
    saveToInbox(email);
    AgentResponse resp = claudeRunner.run(promptBuilder.build(email));
    actionExecutor.execute(resp);
    if (resp.type() == AgentResponseType.NOISE) {
        mailClient.markAsRead(email.id(), email.folder());
    }
    return resp;
}
```

`@EnableScheduling` — на `MailAgentApplication`.

---

## 8. Интеграция с java-memory-service

### Поток REQUEST → PENDING задача

```
MailAgentJob (классифицировал как REQUEST)
        ↓
POST http://localhost:8082/api/tasks/pending
        ↓
java-memory-service сохраняет со статусом PENDING
        ↓
Пользователь видит в UI /ui/today
        ↓
[Принять] → TODO  |  [Изменить] → редактировать  |  [Отклонить] → DELETED
```

Агент **не участвует** в подтверждении. Подтверждение — только через UI.

### Поток CAPTURE → Capture Bot

```
MailAgentJob (классифицировал как CAPTURE)
        ↓
POST http://localhost:8082/api/capture
        ↓
JavaMemoryService сохраняет capture(source=email)
        ↓
CaptureScheduler классифицирует письмо вместе с заметками дня
```

CAPTURE используется для писем с полезной информацией без немедленного действия:
FYI, архитектурные решения, аналитика, плановые работы, новости команды.

### PendingTaskRequest.java — record
```java
public record PendingTaskRequest(
    String title,        // заголовок задачи, извлечённый агентом
    String description,  // краткий контекст из письма (1-2 предложения)
    String emailId,      // Message-ID для трассировки
    String sender,       // email отправителя
    String priority      // LOW | NORMAL | HIGH | CRITICAL
) {}
```

### Правила определения приоритета агентом

| Сигнал в письме | Приоритет |
|-----------------|-----------|
| "срочно", "asap", "до сегодня" | CRITICAL |
| "до завтра", "важно", P1/P2 инцидент | HIGH |
| конкретный дедлайн на этой неделе | NORMAL |
| без дедлайна или "когда будет время" | LOW |

### MemoryServiceClient.java

Использует `java.net.http.HttpClient` — встроен в Java 21, новых зависимостей нет.
Конфигурация через `MailConfig.MemoryServiceProperties` (constructor injection).

```java
@Component
public class MemoryServiceClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final boolean enabled;

    public MemoryServiceClient(ObjectMapper objectMapper, MailConfig.MemoryServiceProperties props) {
        this.objectMapper = objectMapper;
        this.baseUrl = props.getUrl();
        this.enabled = props.isEnabled();
    }

    public void createPendingTask(PendingTaskRequest request) {
        if (!enabled) {
            log.debug("memory-service disabled, skipping");
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/pending"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();

            HttpResponse<String> response =
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.info("Pending task created in memory-service");
            } else {
                log.warn("memory-service returned {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            // Не останавливаем обработку почты если memory-service недоступен
            log.warn("Failed to reach memory-service: {}", e.getMessage());
        }
    }

    public void createCapture(String text, String source) {
        if (!enabled) {
            log.debug("memory-service disabled, skipping capture");
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of("text", text, "source", source));
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/capture"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("Capture saved to memory-service");
            } else {
                log.warn("memory-service /api/capture returned {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.warn("Failed to save capture to memory-service: {}", e.getMessage());
        }
    }

    // Используется StatusController для пинга на /ui/status
    public boolean isHealthy() {
        if (!enabled) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/actuator/health"))
                .GET().timeout(Duration.ofSeconds(2)).build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

## 9. ActionExecutor

```java
@Component
public class ActionExecutor {

    private final MemoryServiceClient memoryServiceClient;

    public void execute(AgentResponse response) throws IOException {
        Path inbox     = resolveInbox(response.emailId());
        Path processed = resolveProcessed(response.emailId());

        switch (response.type()) {
            case REQUEST -> {
                // 1. Дописать в план
                Files.writeString(
                    workDir.resolve(config.getPlanPath()),
                    "\n" + response.taskLine(),
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE
                );
                // 2. Создать PENDING задачу в memory-service
                memoryServiceClient.createPendingTask(new PendingTaskRequest(
                    response.taskTitle(),
                    response.note(),
                    response.emailId(),
                    response.sender(),
                    response.priority()
                ));
                Files.move(inbox, processed, StandardCopyOption.REPLACE_EXISTING);
                log.info("REQUEST → plan + memory-service: {}", response.taskLine());
            }
            case DRAFT -> {
                Files.move(inbox, processed, StandardCopyOption.REPLACE_EXISTING);
                log.info("DRAFT → {}: {}", response.draftPath(), response.note());
            }
            case NOISE -> {
                Files.move(inbox, processed, StandardCopyOption.REPLACE_EXISTING);
                log.info("NOISE: {}", response.note());
            }
            case CAPTURE -> {
                String text = response.captureText() != null && !response.captureText().isBlank()
                    ? response.captureText()
                    : response.note();
                memoryServiceClient.createCapture(text, "email");
                Files.move(inbox, processed, StandardCopyOption.REPLACE_EXISTING);
                log.info("CAPTURE → memory-service: {}", text);
            }
        }
    }
}
```

---

## 10. AgentResponse — обновлённый record

```java
public record AgentResponse(
    AgentResponseType type,
    String emailId,
    String note,         // объяснение решения агента
    String taskLine,     // строка для plans/today.md (только REQUEST)
    String taskTitle,    // заголовок задачи для memory-service (только REQUEST)
    String priority,     // LOW|NORMAL|HIGH|CRITICAL (только REQUEST)
    String sender,       // email отправителя (только REQUEST)
    String draftPath,    // путь к черновику (только DRAFT)
    String captureText   // краткое изложение письма (только CAPTURE)
) {}
```

---

## 11. Клиенты — детали реализации

### MailClient.java — интерфейс
```java
public interface MailClient {
    List<String> listFolders(List<String> excludeFolders) throws MailException;
    List<Email> listUnread(String folder, int limit)      throws MailException;
    void markAsRead(String emailId, String folder)        throws MailException;
    void close();
}
```

`markAsRead` вызывается **только для NOISE** — `REQUEST`, `DRAFT` и `CAPTURE` остаются
непрочитанными в почте, повторная обработка предотвращается через `processed_emails`.

### EwsMailClient — Exchange on-premise ✅ реализован
```java
ExchangeService service = new ExchangeService(ExchangeVersion.Exchange2010_SP2);
service.setCredentials(new WebCredentials(username, password, domain));

if (config.isEwsAutodiscover()) {
    service.autodiscoverUrl(username, redirectionUrl -> true);
} else {
    service.setUrl(new URI(config.getEwsUrl()));
}
```
- `listFolders` — рекурсивный обход `WellKnownFolderName.Inbox` и всех подпапок,
  фильтр `mail.folders.exclude` по leaf-name или полному пути (`Inbox/Team/CI`)
- `listUnread` — `FindItemsResults` + `IsRead = false`, `BodyType.Text`
- `markAsRead` — `email.setIsRead(true); email.update(...)`
- Внешний контракт папки — строковый путь (`Inbox/Subfolder`); внутри клиент держит
  map `folderPath -> FolderId` между `listFolders` и `listUnread`

### ImapMailClient — IMAP (🔜 planned)
```java
// listFolders   — store.getDefaultFolder().list("*"), фильтр excludeFolders
// listUnread    — FlagTerm(Flags.Flag.SEEN, false) в указанной папке
// markAsRead    — message.setFlag(Flags.Flag.SEEN, true)
```

### MaildevClient — HTTP API (только local) ✅ реализован
```java
// listFolders   → всегда возвращает ["INBOX"] (Maildev не имеет папок)
// listUnread    → GET {apiUrl}/email, фильтр "read": false
// markAsRead    → PATCH {apiUrl}/email/{id}/read
```

Выбор клиента — через `@ConditionalOnProperty(name = "mail.protocol")` или
фабрика в `@Configuration` классе.

### Connection check при старте

Каждый клиент проверяет соединение в `@PostConstruct` и пишет в лог:
```
INFO  MaildevClient - ✅ Maildev connection OK — http://172.80.2.1:18080
ERROR EwsMailClient - ❌ EWS connection FAILED — https://mail.company.com/EWS/Exchange.asmx: Connection refused
WARN  MockClaudeRunner - ⚠️  MOCK ClaudeRunner is active — real Claude agent will NOT be called
```
Ошибка не бросает исключение — приложение стартует, проблема будет воспроизводиться в каждом цикле поллинга.

---

## 11a. ClaudeRunner — запуск агента

### ClaudeRunner.java — интерфейс
```java
public interface ClaudeRunner {
    AgentResponse run(String prompt) throws IOException, InterruptedException;
}
```

### ClaudeRunnerImpl.java — реальный запуск (`mock.agent=false`)
```java
@Component
@ConditionalOnProperty(name = "mock.agent", havingValue = "false", matchIfMissing = true)
public class ClaudeRunnerImpl implements ClaudeRunner {
    // Запускает: ProcessBuilder("claude", "--print")
    // Передаёт промпт через stdin, ждёт waitFor(timeoutMinutes, MINUTES)
    // Парсит JSON из stdout: ищет первый { ... } в ответе
}
```

### MockClaudeRunner.java — мок (`mock.agent=true`)

Реальная логика (отличается от CR-001, доработана по результатам тестов):

```java
@Component
@ConditionalOnProperty(name = "mock.agent", havingValue = "true")
public class MockClaudeRunner implements ClaudeRunner {

    // Извлекает секцию письма ДО строки "Верни JSON" из промпта
    // (иначе ключевые слова REQUEST/DRAFT/NOISE/CAPTURE из шаблона мешают классификации)
    private String extractEmailSection(String prompt) {
        int idx = prompt.indexOf("Верни JSON");
        return idx >= 0 ? prompt.substring(0, idx) : prompt;
    }

    // Классификация по русским сигналам в тексте письма
    private AgentResponseType detectType(String emailSection) {
        String upper = emailSection.toUpperCase();
        if (upper.contains("ОТВЕТН") || upper.contains("ЧЕРНОВИК"))
            return AgentResponseType.DRAFT;
        if (upper.contains("BUILD") || upper.contains("PIPELINE") ||
            upper.contains("PASSED") || upper.contains("SUCCESS") || upper.contains("DURATION:"))
            return AgentResponseType.NOISE;
        if (upper.contains("FYI") || upper.contains("К СВЕДЕНИЮ") ||
            upper.contains("ИНФО:") || upper.contains("НАПОМИНАНИЕ:") ||
            upper.contains("CAPTURE"))
            return AgentResponseType.CAPTURE;
        return AgentResponseType.REQUEST;  // default
    }

    // Приоритет по русским сигналам
    private String detectPriority(String emailSection) {
        String upper = emailSection.toUpperCase();
        if (upper.contains("СРОЧНО") || upper.contains("ASAP") || upper.contains("P1 ИНЦИДЕНТ")) return "CRITICAL";
        if (upper.contains("ДО ЗАВТРА") || upper.contains("ВАЖНО") || upper.contains("ДЕДЛАЙН")) return "HIGH";
        if (upper.contains("КОГДА БУДЕТ ВРЕМЯ")) return "LOW";
        return "NORMAL";
    }

    // emailId: парсится regex "emailId": "actual-id" из промпта
    private static final Pattern EMAIL_ID_PATTERN = Pattern.compile("\"emailId\":\\s*\"([^\"]+)\"");
}
```

---

## 12. Persistence — processed_emails

### Схема БД

```
PostgreSQL: leader_framework
└── schema: mailagent   (владелец: mailagent_user)
    └── processed_emails
```

### Таблица

```sql
CREATE TABLE mailagent.processed_emails (
    id            BIGSERIAL PRIMARY KEY,
    email_id      VARCHAR(512) NOT NULL UNIQUE,
    folder        VARCHAR(255),
    sender        VARCHAR(255),
    subject       VARCHAR(512),
    agent_type    VARCHAR(16) NOT NULL,   -- REQUEST | DRAFT | NOISE | CAPTURE
    processed_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### ProcessedEmail.java
```java
@Table("mailagent.processed_emails")
public record ProcessedEmail(
    @Id Long id,
    String emailId,
    String folder,
    String sender,
    String subject,
    String agentType,
    LocalDateTime processedAt
) {
    public static ProcessedEmail of(Email email, String agentType) {
        return new ProcessedEmail(null, email.id(), email.folder(), email.from(),
            email.subject(), agentType, LocalDateTime.now());
    }
}
```

### Миграции (Flyway)

Flyway управляет схемой `mailagent`. Файлы — в `classpath:db/migration/`.

| Версия | Файл | Содержимое |
|--------|------|------------|
| V1 | `V1__create_processed_emails.sql` | Таблица + индексы |

### Инфраструктура

`infra/postgres/init.sql` — скрипт инициализации PostgreSQL.
Выполняется один раз при первом старте контейнера.
Создаёт схемы `mailagent`, `memory`, `rag` и изолированных пользователей.

---

## 13. Web UI

Минимальный статус-экран для мониторинга работы агента.

### StatusController.java
```java
@Controller
public class StatusController {

    private final MailConfig.PathProperties pathProperties;
    private final MemoryServiceClient memoryServiceClient;
    private final String logFile;

    public StatusController(
        MailConfig.PathProperties pathProperties,
        MemoryServiceClient memoryServiceClient,
        @Value("${logging.file.name:logs/mail-agent.log}") String logFile
    ) { ... }

    @GetMapping("/ui/status")
    public String status(Model model) {
        model.addAttribute("inboxCount", countFiles(pathProperties.getInbox()));
        model.addAttribute("processedCount", countFiles(pathProperties.getProcessed()));
        model.addAttribute("draftsCount", countFiles(pathProperties.getDrafts()));
        model.addAttribute("recentLogs", readRecentLogs(50));   // читает log-файл напрямую
        model.addAttribute("memoryServiceHealthy", memoryServiceClient.isHealthy());
        return "status";
    }
}
```

### status.html — Thymeleaf
Показывает:
- количество писем в `inbox/`, `processed/`, `drafts/`
- последние 50 строк лога (читает файл `logs/mail-agent.log` напрямую)
- статус подключения к `java-memory-service` (ping на `/actuator/health`)

---

## 14. Main

```java
@SpringBootApplication
@EnableScheduling
public class MailAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailAgentApplication.class, args);
    }
}
```

---

## 15. pom.xml — координаты

```xml
<groupId>ru.andreyz.mailagent</groupId>
<artifactId>mail-agent</artifactId>
<version>1.0.0</version>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.0</version>
</parent>

<properties>
    <java.version>21</java.version>
</properties>
```

Корневой пакет всех классов: `ru.andreyz.mailagent`

---

## 16. Сборка и запуск

```bash
cd JavaMailAgent
mvn package -q

# Запуск — из корня Leader-Role-Framework (Claude видит CLAUDE.md)
cd ..

# Поднять инфраструктуру (PostgreSQL + OpenSearch)
docker compose up -d
# + Maildev (только local)
docker compose --profile local up -d

# local (Maildev + PostgreSQL) — по умолчанию
POSTGRES_MAILAGENT_PASSWORD=mailagent_password \
  java -jar JavaMailAgent/target/mail-agent-1.0.0.jar \
  --spring.profiles.active=local

# dev
MAIL_PASSWORD=secret \
  java -jar JavaMailAgent/target/mail-agent-1.0.0.jar \
  --spring.profiles.active=dev

# prod
MAIL_PASSWORD=secret \
  java -jar JavaMailAgent/target/mail-agent-1.0.0.jar \
  --spring.profiles.active=prod

# UI доступен на
open http://localhost:8080/ui/status
```

---

## 17. .gitignore

```
application-local.yml
application-dev.yml
application-prod.yml
target/
logs/
```

В git — только `application.yml` и `*.example` шаблоны.

---

## 18. SMTP — Future

SMTP нужен только для **отправки**. В MVP не реализуется.

Когда понадобится:
- `SmtpSender.java` через Jakarta Mail
- Новый тип `SEND` в `AgentResponseType`
- Конфиг `smtp.*` уже есть в `application-prod.yml.example`

---

## 19. Порядок реализации

### Реализовано ✅
1. `JavaMailAgent/docker-compose.yml` — Maildev (порт 18080)
2. `pom.xml` — Spring Boot 3.3, Jakarta Mail, Logback, Spring Data JDBC, Flyway
3. `application.yml` + `application-local.yml`
4. `MailAgentApplication.java` — `@SpringBootApplication` + `@EnableScheduling`
5. `MailConfig.java` — `@ConfigurationProperties` (вложенные static классы)
6. `Email`, `AgentResponseType`, `AgentResponse`, `PendingTaskRequest`, `ProcessedEmail` — records
7. `MailClient.java` — интерфейс + `MailException`
8. `MaildevClient.java` — HTTP API + `@PostConstruct` connection check
9. `EwsMailClient.java` — Exchange/EWS, рекурсивный scan подпапок Inbox, exclude-фильтр
10. `application-prod.yml` — prod placeholders для EWS и исключённых папок
11. `PromptBuilder.java` — формирует промпт из `Email`
12. `ClaudeRunnerImpl.java` — Process + waitFor + парсинг JSON
13. `MockClaudeRunner.java` — мок с логикой по русским ключевым словам
14. `MemoryServiceClient.java` — POST /api/tasks/pending + isHealthy()
15. `ActionExecutor.java` — switch по enum + вызов MemoryServiceClient
16. `MailAgentJob.java` — `@Scheduled(fixedDelay)`, мульти-папки, dedup через processed_emails
17. `ProcessedEmailRepository.java` + `V1__create_processed_emails.sql`
18. `StatusController.java` + `status.html` — Web UI
19. `logback-spring.xml` — ротация логов по профилям

### Planned 🔜
20. `ImapMailClient.java` — dev окружение (IMAP)
21. E2E тесты: письмо → Maildev → агент → `plans/today.md` + memory-service
