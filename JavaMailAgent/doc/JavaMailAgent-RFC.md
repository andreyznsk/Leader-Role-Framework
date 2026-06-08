# RFC: JavaMailAgent — Java Application Core

**Статус:** Draft  
**Дата:** 2026-06-06  
**Проект:** Leader-Role-Framework / JavaMailAgent  
**Запускать Claude Code из:** `Leader-Role-Framework/JavaMailAgent/`

---

## 1. Обзор

Java 21 приложение. Подключается к корпоративному почтовому серверу,
читает новые письма, для каждого запускает Claude-агента, выполняет
детерминированное действие по результату.

Работает как бесконечный фоновый процесс. Никакого UI.

---

## 2. Стек и зависимости

| Что | Версия | Зачем |
|-----|--------|-------|
| Java | 21 | Records, sealed classes, switch expressions |
| Maven | 3.9+ | Сборка |
| EWS Java API | 2.0 | Microsoft Exchange on-premise |
| Jakarta Mail | 2.0.1 | IMAP + SMTP |
| Jackson Databind | 2.17 | JSON: AgentResponse, Email → файл |
| OkHttp | 4.12 | HTTP API для Maildev (локальное тестирование) |
| SLF4J Simple | 2.0 | Логи в stderr |
| maven-shade-plugin | 3.5 | Fat-jar |

**Никаких Spring** — приложение простое, фреймворк не нужен.

---

## 3. Окружения и конфиги

### Концепция

Рядом с JAR лежат файлы `application-{ENV}.properties`.
Окружение выбирается через аргумент `--env` или переменную среды `APP_ENV`.
По умолчанию — `local`.

```
target/
├── mail-agent-1.0.0.jar
├── application-local.properties       ← локальное тестирование (Maildev)
├── application-dev.properties         ← dev-стенд (IMAP)
└── application-prod.properties        ← продакшн (EWS + Exchange)
```

В git кладём только `application-{ENV}.properties.example` — шаблоны без паролей.
Реальные конфиги — в `.gitignore`.

### Приоритет загрузки конфига

1. `--env НАЗВАНИЕ` → ищет `application-НАЗВАНИЕ.properties` рядом с JAR
2. `APP_ENV` env-переменная → то же самое
3. По умолчанию → `application-local.properties`
4. Пароль: env `MAIL_PASSWORD` всегда имеет приоритет над файлом

### application-local.properties.example — Maildev (Docker)
```properties
# Локальное тестирование через Maildev в Docker
mail.protocol=maildev

maildev.api.url=http://localhost:1080
maildev.smtp.host=localhost
maildev.smtp.port=1025

# Пути (относительно корня Leader-Role-Framework)
path.inbox=inbox
path.processed=processed
path.drafts=drafts
path.plan=plans/today.md

mail.fetch.limit=20
poll.interval.seconds=30
agent.timeout.minutes=5
```

### application-dev.properties.example — IMAP стенд
```properties
mail.protocol=imap

mail.username=user@company.com
mail.password=

imap.host=mail.dev.company.com
imap.port=993
imap.ssl=true
imap.folder=INBOX

path.inbox=inbox
path.processed=processed
path.drafts=drafts
path.plan=plans/today.md

mail.fetch.limit=20
poll.interval.seconds=60
agent.timeout.minutes=5
```

### application-prod.properties.example — Exchange (EWS)
```properties
mail.protocol=ews

mail.username=user@company.com
mail.password=

ews.url=https://mail.company.com/EWS/Exchange.asmx
ews.autodiscover=false
ews.domain=

# SMTP для будущей отправки (MVP — не используется)
smtp.host=mail.company.com
smtp.port=587
smtp.starttls=true

path.inbox=inbox
path.processed=processed
path.drafts=drafts
path.plan=plans/today.md

mail.fetch.limit=20
poll.interval.seconds=60
agent.timeout.minutes=5
```

---

## 4. Docker Compose — локальное тестирование

```yaml
# docker-compose.yml (в корне JavaMailAgent/)
services:
  maildev:
    image: maildev/maildev:latest
    ports:
      - "1080:1080"   # Web UI + HTTP API
      - "1025:1025"   # SMTP (принимает письма)
    environment:
      - MAILDEV_INCOMING_USER=test
      - MAILDEV_INCOMING_PASS=test
```

```bash
docker compose up -d

# Web UI для ручной проверки
open http://localhost:1080

# Отправить тестовое письмо через SMTP
curl -s --url "smtp://localhost:1025" \
  --mail-from "sender@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<EOF
Subject: Нужен ревью PR #42
From: sender@test.com
To: me@test.com

Привет, можешь посмотреть PR #42?
EOF
```

---

## 5. Структура проекта

```
JavaMailAgent/
├── CLAUDE.md
├── RFC-java-core.md                       ← этот документ
├── docker-compose.yml                     ← Maildev для локальных тестов
├── application-local.properties.example
├── application-dev.properties.example
├── application-prod.properties.example
├── pom.xml
└── src/
    ├── main/java/com/mailagent/
    │   ├── Main.java
    │   ├── config/
    │   │   └── MailConfig.java
    │   ├── client/
    │   │   ├── MailClient.java            ← интерфейс
    │   │   ├── EwsMailClient.java         ← Exchange on-premise (prod)
    │   │   ├── ImapMailClient.java        ← IMAP (dev)
    │   │   └── MaildevClient.java         ← HTTP API Maildev (local)
    │   ├── model/
    │   │   ├── Email.java
    │   │   ├── AgentResponse.java
    │   │   └── AgentResponseType.java
    │   └── scheduler/
    │       ├── MailAgentJob.java
    │       ├── PromptBuilder.java
    │       ├── ClaudeRunner.java
    │       └── ActionExecutor.java
    └── test/java/com/mailagent/
        ├── client/MaildevClientTest.java
        └── scheduler/ActionExecutorTest.java
```

---

## 6. Клиенты — детали реализации

### MailClient.java — интерфейс (единый для всех)
```java
public interface MailClient extends AutoCloseable {
    List<Email> listUnread(int limit) throws MailException;
    void markAsRead(String emailId)   throws MailException;

    @Override
    void close();
}
```

`MailException` — unchecked, оборачивает все транспортные ошибки.

---

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

- `listUnread` — `FindItemsResults` + фильтр `IsRead = false`, сортировка по дате DESC
- Body — запрашивать как `BodyType.Text` (не HTML)
- `markAsRead` — `email.setIsRead(true); email.update(ConflictResolutionMode.AlwaysOverwrite)`

---

### ImapMailClient — IMAP
```java
Properties props = new Properties();
props.put("mail.imap.host",       config.getImapHost());
props.put("mail.imap.port",       config.getImapPort());
props.put("mail.imap.ssl.enable", config.isImapSsl());

Session session = Session.getInstance(props);
Store store     = session.getStore("imap");
store.connect(username, password);

Folder folder = store.getFolder(config.getImapFolder());
folder.open(Folder.READ_WRITE);
```

- `listUnread` — `folder.search(new FlagTerm(Flags.Flag.SEEN, false))`
- `markAsRead` — `message.setFlag(Flags.Flag.SEEN, true)`

---

### MaildevClient — HTTP API (только для local)
```java
// GET http://localhost:1080/email — список всех писем
// PATCH http://localhost:1080/email/{id}/read — пометить прочитанным
// DELETE http://localhost:1080/email/all — очистить ящик

OkHttpClient http = new OkHttpClient();

// listUnread: GET /email, фильтруем по "read": false
Request request = new Request.Builder()
    .url(config.getMaildevApiUrl() + "/email")
    .build();
Response response = http.newCall(request).execute();
// парсим JSON массив, берём только { "read": false }
```

Maildev не требует авторизации по умолчанию — Basic Auth опционален через env.

---

## 7. Модели данных

### Email.java — record
```java
public record Email(
    String id,
    String subject,
    String from,
    List<String> to,
    List<String> cc,
    Instant date,
    String body       // всегда plain text, HTML стрипается при чтении
) {}
```

При сохранении в `inbox/{id}.json`:
- `body` обрезается до 10 000 символов, добавляется `\n[truncated]`
- имя файла — `URLEncoder.encode(id)` (EWS ID содержит спецсимволы)

### AgentResponseType.java
```java
public enum AgentResponseType {
    DRAFT,    // агент подготовил черновик → файл уже в drafts/
    REQUEST,  // нужно действие техлида → добавить в план
    NOISE     // не требует внимания
}
```

### AgentResponse.java — record
```java
public record AgentResponse(
    AgentResponseType type,
    String emailId,
    String note,        // объяснение решения агента
    String taskLine,    // заполнено только для REQUEST
    String draftPath    // заполнено только для DRAFT
) {}
```

---

## 8. Главный цикл — MailAgentJob

Один поток, письма обрабатываются последовательно.
`scheduleWithFixedDelay` — следующий тик только после завершения предыдущего.

```
MailAgentJob.run():
  1. client.listUnread(limit)
  2. для каждого email:
     a. сохранить в inbox/{id}.json
     b. prompt = PromptBuilder.build(email)
     c. response = ClaudeRunner.run(prompt)
     d. ActionExecutor.execute(response)
     e. client.markAsRead(email.id())    ← только после успеха
  3. client.close()
```

Если ClaudeRunner бросил исключение — письмо остаётся в `inbox/`,
не помечается прочитанным, логируется ошибка. Следующий тик повторит.

---

## 9. ClaudeRunner

```java
public AgentResponse run(String prompt, Path workDir)
        throws IOException, InterruptedException {

    ProcessBuilder pb = new ProcessBuilder("claude", "--print", prompt);
    pb.directory(workDir.toFile());
    pb.redirectErrorStream(false);

    Process process = pb.start();
    String stdout   = new String(
        process.getInputStream().readAllBytes(), StandardCharsets.UTF_8
    );

    boolean finished = process.waitFor(
        config.getAgentTimeoutMinutes(), TimeUnit.MINUTES
    );
    if (!finished) {
        process.destroyForcibly();
        throw new AgentTimeoutException("Claude timed out");
    }
    if (process.exitValue() != 0) {
        throw new AgentException("Claude exited with code " + process.exitValue());
    }

    return objectMapper.readValue(stdout.trim(), AgentResponse.class);
}
```

---

## 10. ActionExecutor

```java
public void execute(AgentResponse response) throws IOException {
    Path inbox     = workDir.resolve(config.getInboxPath())
                            .resolve(safeFilename(response.emailId()) + ".json");
    Path processed = workDir.resolve(config.getProcessedPath())
                            .resolve(safeFilename(response.emailId()) + ".json");

    switch (response.type()) {
        case REQUEST -> {
            Files.writeString(
                workDir.resolve(config.getPlanPath()),
                "\n" + response.taskLine(),
                StandardOpenOption.APPEND, StandardOpenOption.CREATE
            );
            Files.move(inbox, processed, StandardCopyOption.REPLACE_EXISTING);
            log.info("REQUEST → plan: {}", response.taskLine());
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
```

---

## 11. Main.java

```java
public static void main(String[] args) {
    String env    = resolveEnv(args);           // --env или APP_ENV или "local"
    MailConfig config = MailConfig.load(env);   // application-{env}.properties рядом с JAR

    Path workDir = Path.of(System.getProperty("user.dir")); // корень Leader-Role-Framework

    MailClient client = switch (config.getProtocol()) {
        case "ews"     -> new EwsMailClient(config);
        case "imap"    -> new ImapMailClient(config);
        case "maildev" -> new MaildevClient(config);
        default        -> throw new IllegalArgumentException(
                              "Unknown protocol: " + config.getProtocol());
    };

    MailAgentJob job = new MailAgentJob(client, config, workDir);

    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    scheduler.scheduleWithFixedDelay(
        job::run, 0, config.getPollIntervalSeconds(), TimeUnit.SECONDS
    );

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        scheduler.shutdown();
        client.close();
    }));
}

private static String resolveEnv(String[] args) {
    // 1. --env local|dev|prod из аргументов
    for (int i = 0; i < args.length - 1; i++) {
        if ("--env".equals(args[i])) return args[i + 1];
    }
    // 2. APP_ENV переменная среды
    String envVar = System.getenv("APP_ENV");
    if (envVar != null && !envVar.isBlank()) return envVar;
    // 3. default
    return "local";
}
```

### MailConfig.load(env) — откуда читать файл
```java
public static MailConfig load(String env) {
    String filename = "application-" + env + ".properties";

    // 1. Рядом с JAR
    Path nearJar = Path.of(filename);
    if (Files.exists(nearJar)) return parse(Files.newInputStream(nearJar));

    // 2. Classpath (для тестов)
    InputStream cp = MailConfig.class.getResourceAsStream("/" + filename);
    if (cp != null) return parse(cp);

    throw new IllegalStateException("Config not found: " + filename);
}
```

---

## 12. Сборка и запуск

```bash
# Сборка
cd JavaMailAgent
mvn package -q

# Запуск — из корня Leader-Role-Framework (Claude видит CLAUDE.md)
cd ..

# local (Maildev в Docker) — по умолчанию
java -jar JavaMailAgent/target/mail-agent-1.0.0.jar

# dev (IMAP стенд)
APP_ENV=dev MAIL_PASSWORD=secret \
  java -jar JavaMailAgent/target/mail-agent-1.0.0.jar

# prod (Exchange)
APP_ENV=prod MAIL_PASSWORD=secret \
  java -jar JavaMailAgent/target/mail-agent-1.0.0.jar

# или через аргумент
java -jar JavaMailAgent/target/mail-agent-1.0.0.jar --env prod
```

---

## 13. .gitignore

```
# Реальные конфиги с паролями — не в git
application-local.properties
application-dev.properties
application-prod.properties

target/
*.class
```

В git попадают только `application-*.properties.example` — шаблоны без паролей.

---

## 14. SMTP — Future

SMTP нужен только для **отправки** (не для чтения). В MVP не реализуется.

Когда понадобится:
- Добавить `SmtpSender.java` с Jakarta Mail
- Новый тип AgentResponse: `SEND` — агент уверен в ответе и разрешает отправку
- Конфиг `smtp.*` уже есть в `application-prod.properties.example`

---

## 15. Порядок реализации

1. `docker-compose.yml` — поднять Maildev, проверить Web UI на `:1080`
2. `pom.xml` — зависимости, shade plugin, Java 21
3. `MailConfig.java` — загрузка `application-{env}.properties` + env override
4. `Email`, `AgentResponseType`, `AgentResponse` — records
5. `MailClient.java` — интерфейс + `MailException`
6. `MaildevClient.java` — HTTP API, `listUnread` + `markAsRead` (первый тест)
7. `PromptBuilder.java` — формирует промпт из `Email`
8. `ClaudeRunner.java` — Process + waitFor + парсинг JSON
9. `ActionExecutor.java` — switch по enum
10. `MailAgentJob.java` — главный цикл
11. `Main.java` — env resolution + scheduleWithFixedDelay + shutdown hook
12. Сквозной тест: письмо через SMTP → Maildev → агент → `plans/today.md`
13. `ImapMailClient.java` — IMAP (dev окружение)
14. `EwsMailClient.java` — Exchange (prod окружение)
15. Fat-jar, проверка запуска с `--env prod`
