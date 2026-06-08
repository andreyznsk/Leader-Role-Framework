# RFC: JavaMailAgent — Java Application Core

**Статус:** Draft  
**Дата:** 2026-06-06  
**Проект:** Leader-Role-Framework / JavaMailAgent  
**Запускать Claude Code из:** `Leader-Role-Framework/JavaMailAgent/`

---

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
| maven-shade-plugin | 3.5 | Fat-jar |

**Без Spring Data, без БД** — mail-agent не хранит состояние сам,
для этого есть `java-memory-service`.

---

## 3. Окружения и конфиги

### Концепция

Spring Boot нативно поддерживает профили через `application-{ENV}.properties`.
Файлы лежат рядом с JAR. Профиль выбирается через `--spring.profiles.active`
или `SPRING_PROFILES_ACTIVE`.

```
target/
├── mail-agent-1.0.0.jar
├── application.properties             ← общие настройки (в git)
├── application-local.properties       ← Maildev Docker (НЕ в git)
├── application-dev.properties         ← IMAP стенд (НЕ в git)
└── application-prod.properties        ← Exchange on-premise (НЕ в git)
```

В git — только `application.properties` и `application-*.properties.example`.

### application.properties — общие настройки (в git)
```properties
spring.application.name=mail-agent

# Scheduling — один поток, fixedDelay
mail.poll.interval.seconds=60
agent.timeout.minutes=5
mail.fetch.limit=20

# Пути (относительно рабочей директории = корень Leader-Role-Framework)
path.inbox=inbox
path.processed=processed
path.drafts=drafts
path.plan=plans/today.md

# java-memory-service интеграция
memory.service.url=http://localhost:8082
memory.service.tasks.pending.path=/api/tasks/pending
memory.service.enabled=true

# Логи
logging.file.name=logs/mail-agent.log
logging.logback.rollingpolicy.max-file-size=10MB
logging.logback.rollingpolicy.max-history=30
logging.pattern.file=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
logging.pattern.console=%d{HH:mm:ss} %-5level %logger{36} - %msg%n
```

### application-local.properties.example — Maildev
```properties
spring.profiles.active=local
mail.protocol=maildev

maildev.api.url=http://localhost:1080
maildev.smtp.host=localhost
maildev.smtp.port=1025

memory.service.enabled=false
mail.poll.interval.seconds=30
```

### application-dev.properties.example — IMAP стенд
```properties
spring.profiles.active=dev
mail.protocol=imap

mail.username=user@company.com
mail.password=

imap.host=mail.dev.company.com
imap.port=993
imap.ssl=true
imap.folder=INBOX
```

### application-prod.properties.example — Exchange
```properties
spring.profiles.active=prod
mail.protocol=ews

mail.username=user@company.com
mail.password=

ews.url=https://mail.company.com/EWS/Exchange.asmx
ews.autodiscover=false
ews.domain=

# SMTP — Future (отправка черновиков)
smtp.host=mail.company.com
smtp.port=587
smtp.starttls=true
```

---

## 4. Docker Compose — локальное тестирование

```yaml
# docker-compose.yml
services:
  maildev:
    image: maildev/maildev:latest
    ports:
      - "1080:1080"   # Web UI + HTTP API
      - "1025:1025"   # SMTP
```

```bash
docker compose up -d

# Отправить тестовое письмо
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
├── RFC-java-core.md
├── docker-compose.yml
├── application.properties.example
├── application-local.properties.example
├── application-dev.properties.example
├── application-prod.properties.example
├── pom.xml
└── src/
    ├── main/
    │   ├── java/ru/andreyz/mailagent/
    │   │   ├── MailAgentApplication.java       ← @SpringBootApplication
    │   │   ├── config/
    │   │   │   └── MailConfig.java             ← @ConfigurationProperties
    │   │   ├── client/
    │   │   │   ├── MailClient.java             ← интерфейс
    │   │   │   ├── EwsMailClient.java          ← Exchange (prod)
    │   │   │   ├── ImapMailClient.java         ← IMAP (dev)
    │   │   │   └── MaildevClient.java          ← HTTP API (local)
    │   │   ├── model/
    │   │   │   ├── Email.java                  ← record
    │   │   │   ├── AgentResponse.java          ← record
    │   │   │   ├── AgentResponseType.java      ← enum: DRAFT/REQUEST/NOISE
    │   │   │   └── PendingTaskRequest.java     ← record, payload для memory-service
    │   │   ├── scheduler/
    │   │   │   ├── MailAgentJob.java           ← @Scheduled, главный цикл
    │   │   │   ├── PromptBuilder.java          ← формирует промпт для Claude
    │   │   │   ├── ClaudeRunner.java           ← запускает Process, читает stdout
    │   │   │   └── ActionExecutor.java         ← switch по enum
    │   │   ├── integration/
    │   │   │   └── MemoryServiceClient.java    ← POST /api/tasks/pending
    │   │   └── web/
    │   │       └── StatusController.java       ← UI: /ui/status
    │   └── resources/
    │       ├── application.properties
    │       ├── logback-spring.xml              ← конфиг логов
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
10:32:10 INFO  MailAgentJob        - Poll started, fetching up to 20 unread emails
10:32:11 INFO  MailAgentJob        - Found 3 unread emails
10:32:11 INFO  MailAgentJob        - Processing email AAMk-123 from ivanov@company.ru: "Обсудить архитектуру payments"
10:32:14 INFO  ClaudeRunner        - Classified as REQUEST, priority HIGH
10:32:14 INFO  MemoryServiceClient - Pending task created, id=42
10:32:14 INFO  ActionExecutor      - REQUEST → plan: "- [ ] [P1] Обсудить архитектуру payments — от ivanov@company.ru"
10:32:14 INFO  MailAgentJob        - Email AAMk-123 marked as read, moved to processed/
10:32:15 INFO  MailAgentJob        - Processing email AAMk-456 from ci@jenkins.local: "Build #321 passed"
10:32:17 INFO  ClaudeRunner        - Classified as NOISE
10:32:17 INFO  ActionExecutor      - NOISE: CI уведомление, пропускаем
10:32:17 INFO  MailAgentJob        - Email AAMk-456 marked as read, moved to processed/
10:32:18 INFO  MailAgentJob        - Poll finished: 2 processed (1 REQUEST, 1 NOISE), 1 error
10:32:18 WARN  MailAgentJob        - Email AAMk-789 failed: Claude timed out after 5 minutes, will retry
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

```java
@Component
public class MailAgentJob {

    private final MailClient mailClient;
    private final PromptBuilder promptBuilder;
    private final ClaudeRunner claudeRunner;
    private final ActionExecutor actionExecutor;

    // fixedDelay — следующий тик только после завершения предыдущего
    // Пока метод выполняется — новый тик не запускается (один поток)
    @Scheduled(fixedDelayString = "${mail.poll.interval.seconds}000")
    public void poll() {
        log.info("Poll started, fetching up to {} unread emails", config.getFetchLimit());
        List<Email> emails = mailClient.listUnread(config.getFetchLimit());
        log.info("Found {} unread emails", emails.size());

        int errors = 0;
        for (Email email : emails) {
            try {
                processEmail(email);
            } catch (Exception e) {
                errors++;
                log.warn("Email {} failed: {}, will retry", email.id(), e.getMessage());
            }
        }
        log.info("Poll finished: {} processed ({} errors)", emails.size(), errors);
    }

    private void processEmail(Email email) throws Exception {
        saveToInbox(email);
        String prompt       = promptBuilder.build(email);
        AgentResponse resp  = claudeRunner.run(prompt);
        actionExecutor.execute(resp);
        mailClient.markAsRead(email.id());
    }
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

```java
@Component
public class MemoryServiceClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;

    @Value("${memory.service.url}")
    private String baseUrl;

    @Value("${memory.service.enabled}")
    private boolean enabled;

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
    String draftPath     // путь к черновику (только DRAFT)
) {}
```

---

## 11. Клиенты — детали реализации

### MailClient.java — интерфейс
```java
public interface MailClient {
    List<Email> listUnread(int limit) throws MailException;
    void markAsRead(String emailId)   throws MailException;
    void close();
}
```

### EwsMailClient — Exchange on-premise
```java
ExchangeService service = new ExchangeService(ExchangeVersion.Exchange2010_SP2);
service.setCredentials(new WebCredentials(username, password, domain));

if (config.isEwsAutodiscover()) {
    service.autodiscoverUrl(username, redirectionUrl -> true);
} else {
    service.setUrl(new URI(config.getEwsUrl()));
}
```
- `listUnread` — `FindItemsResults` + `IsRead = false`, `BodyType.Text`
- `markAsRead` — `email.setIsRead(true); email.update(...)`

### ImapMailClient — IMAP
```java
// listUnread — FlagTerm(Flags.Flag.SEEN, false)
// markAsRead — message.setFlag(Flags.Flag.SEEN, true)
```

### MaildevClient — HTTP API (только local)
```java
// GET  {maildev.api.url}/email          → список писем, фильтр "read": false
// PATCH {maildev.api.url}/email/{id}/read → пометить прочитанным
```

Выбор клиента — через `@ConditionalOnProperty(name = "mail.protocol")` или
фабрика в `@Configuration` классе.

---

## 12. Web UI

Минимальный статус-экран для мониторинга работы агента.

### StatusController.java
```java
@Controller
public class StatusController {

    @GetMapping("/ui/status")
    public String status(Model model) {
        model.addAttribute("recentLogs", logReader.getRecentLines(50));
        model.addAttribute("inboxCount", countFiles(inboxPath));
        model.addAttribute("processedCount", countFiles(processedPath));
        model.addAttribute("draftsCount", countFiles(draftsPath));
        return "status";
    }
}
```

### status.html — Thymeleaf
Показывает:
- количество писем в `inbox/`, `processed/`, `drafts/`
- последние 50 строк лога
- статус подключения к `java-memory-service` (ping)

---

## 13. Main

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

## 14. pom.xml — координаты

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

## 15. Сборка и запуск

```bash
cd JavaMailAgent
mvn package -q

# Запуск — из корня Leader-Role-Framework (Claude видит CLAUDE.md)
cd ..

# local (Maildev) — по умолчанию
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

## 15. .gitignore

```
application-local.properties
application-dev.properties
application-prod.properties
target/
logs/
```

В git — только `application.properties` и `*.example` шаблоны.

---

## 16. SMTP — Future

SMTP нужен только для **отправки**. В MVP не реализуется.

Когда понадобится:
- `SmtpSender.java` через Jakarta Mail
- Новый тип `SEND` в `AgentResponseType`
- Конфиг `smtp.*` уже есть в `application-prod.properties.example`

---

## 17. Порядок реализации

1. `docker-compose.yml` — поднять Maildev, проверить UI на `:1080`
2. `pom.xml` — Spring Boot 3.3, EWS, Jakarta Mail, Logback
3. `application.properties` + профильные `*.example`
4. `MailAgentApplication.java` — `@SpringBootApplication` + `@EnableScheduling`
5. `MailConfig.java` — `@ConfigurationProperties`
6. `Email`, `AgentResponseType`, `AgentResponse`, `PendingTaskRequest` — records
7. `MailClient.java` — интерфейс + `MailException`
8. `MaildevClient.java` — HTTP API (первый рабочий клиент)
9. `PromptBuilder.java` — формирует промпт из `Email`
10. `ClaudeRunner.java` — Process + waitFor + парсинг JSON
11. `MemoryServiceClient.java` — POST /api/tasks/pending
12. `ActionExecutor.java` — switch по enum + вызов MemoryServiceClient
13. `MailAgentJob.java` — `@Scheduled(fixedDelay)`
14. `StatusController.java` + `status.html` — Web UI
15. `logback-spring.xml` — ротация логов по профилям
16. Сквозной тест: письмо через SMTP → Maildev → агент → `plans/today.md` + memory-service
17. `ImapMailClient.java` — dev окружение
18. `EwsMailClient.java` — prod окружение
19. Fat-jar, проверка всех трёх профилей
