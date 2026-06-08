# RFC: java-memory-service

**Статус:** Draft  
**Автор:** Андрей Зайцев  
**Дата:** 2026-06-08  
**Порт:** 8082  

---

## Контекст системы
Перед работой с интеграциями читай `ARCHITECTURE.md`.

## 1. Назначение

`java-memory-service` — локальный Spring Boot 3 процесс (Java 21), который:

- Хранит оперативный контекст Tech Lead в PostgreSQL (prod) / H2 (local/test)
- Предоставляет Thymeleaf UI для просмотра и ручного редактирования данных
- Предоставляет MCP-интерфейс для Claude Agent (чтение/запись задач, планов, инцидентов)
- Принимает предложения задач от java-mail-agent (статус PENDING) и ждёт подтверждения через UI

---

## 2. Стек

| Компонент | Библиотека |
|-----------|-----------|
| Framework | Spring Boot 3.3.x |
| Web | Spring MVC (embedded Tomcat) |
| UI | Thymeleaf + Bootstrap 5 CDN |
| Data | Spring Data JDBC (без Hibernate, без JPA) |
| Миграции | Flyway |
| DB prod | PostgreSQL 15 (Docker) |
| DB local/test | H2 (in-memory, MODE=PostgreSQL) |
| JSON | Jackson (встроен в Boot) |
| MCP | spring-ai-mcp-server-spring-boot-starter |
| Тесты | JUnit 5, Spring Boot Test, MockMvc |
| Build | Maven, fat JAR через spring-boot-maven-plugin |

---

## 3. Профили

### `local` (default при разработке)
```properties
# application-local.properties
spring.profiles.active=local
spring.datasource.url=jdbc:h2:mem:techlead;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.h2.console.enabled=true
spring.h2.console.path=/h2-console
spring.flyway.locations=classpath:db/migration,classpath:db/migration-h2
```

### `prod`
```properties
# application-prod.properties
spring.datasource.url=jdbc:postgresql://localhost:5432/techlead
spring.datasource.username=techlead
spring.datasource.password=techlead
spring.datasource.hikari.maximum-pool-size=5
spring.flyway.locations=classpath:db/migration
```

### `test`
```properties
# application-test.properties
spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.flyway.locations=classpath:db/migration,classpath:db/migration-h2
```

**Запуск** (из корня проекта `Leader-Role-Framework/`, как указано в ARCHITECTURE.md):
```bash
# local (H2)
SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar

# prod (PostgreSQL)
SPRING_PROFILES_ACTIVE=prod java -jar JavaMemoryService/target/memory-service.jar
```

---

## 4. База данных

### 4.1 Схема (V1__init_schema.sql)

```sql
-- Ежедневные планы
CREATE TABLE daily_plans (
    id         BIGSERIAL PRIMARY KEY,
    plan_date  DATE         NOT NULL UNIQUE,
    summary    TEXT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DONE | CANCELLED
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Задачи
CREATE TABLE tasks (
    id          BIGSERIAL PRIMARY KEY,
    plan_id     BIGINT       REFERENCES daily_plans(id) ON DELETE CASCADE,
    title       VARCHAR(500) NOT NULL,
    description TEXT,
    status      VARCHAR(20)  NOT NULL DEFAULT 'TODO',    -- PENDING | TODO | IN_PROGRESS | DONE | BLOCKED | DELETED
    priority    VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',  -- LOW | NORMAL | HIGH | CRITICAL
    due_date    DATE,
    source      VARCHAR(20)  NOT NULL DEFAULT 'MANUAL',  -- MANUAL | EMAIL | AGENT
    email_id    VARCHAR(500),                            -- ссылка на письмо если source=EMAIL
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Инциденты
CREATE TABLE incidents (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(500) NOT NULL,
    severity     VARCHAR(10)  NOT NULL,                  -- P1 | P2 | P3
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN',   -- OPEN | INVESTIGATING | RESOLVED
    description  TEXT,
    root_cause   TEXT,
    action_items TEXT,
    started_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Риски
CREATE TABLE risks (
    id          BIGSERIAL PRIMARY KEY,
    title       VARCHAR(500) NOT NULL,
    description TEXT,
    probability VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',  -- LOW | MEDIUM | HIGH
    impact      VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',  -- LOW | MEDIUM | HIGH
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',    -- OPEN | MITIGATED | ACCEPTED | CLOSED
    mitigation  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Люди (карточки членов команды и стейкхолдеров)
CREATE TABLE people (
    id               BIGSERIAL PRIMARY KEY,
    full_name        VARCHAR(200) NOT NULL,
    login            VARCHAR(100),
    email            VARCHAR(200),
    phone            VARCHAR(50),
    domain           VARCHAR(200),  -- сфера деятельности / роль
    current_task     TEXT,          -- задача над которой работает сейчас
    capacity_sprint  INT,           -- доступная ёмкость в спринте (часы или %)
    capacity_month   INT,
    capacity_quarter INT,
    notes            TEXT,          -- общие заметки
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Хронологические заметки по людям
CREATE TABLE people_notes (
    id          BIGSERIAL PRIMARY KEY,
    person_id   BIGINT       REFERENCES people(id) ON DELETE CASCADE,
    note        TEXT         NOT NULL,
    tags        VARCHAR(500),   -- через запятую: trust,blocker,key-person
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Состояние входящей почты
CREATE TABLE email_state (
    id             BIGSERIAL PRIMARY KEY,
    message_id     VARCHAR(500) UNIQUE NOT NULL,
    subject        VARCHAR(1000),
    sender         VARCHAR(500),
    received_at    TIMESTAMPTZ,
    classification VARCHAR(20),   -- DRAFT | REQUEST | NOISE
    status         VARCHAR(20)  NOT NULL DEFAULT 'NEW',  -- NEW | PROCESSED | IGNORED
    summary        TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Индексы
CREATE INDEX idx_tasks_plan_id    ON tasks(plan_id);
CREATE INDEX idx_tasks_status     ON tasks(status);
CREATE INDEX idx_tasks_source     ON tasks(source);
CREATE INDEX idx_daily_plans_date ON daily_plans(plan_date);
CREATE INDEX idx_incidents_status ON incidents(status);
CREATE INDEX idx_risks_status     ON risks(status);
CREATE INDEX idx_people_notes_person ON people_notes(person_id);
```

### 4.2 H2 compatibility (V1_1__h2_compat.sql)

H2 в режиме `MODE=PostgreSQL` поддерживает `BIGSERIAL`, `TIMESTAMPTZ`, `BIGINT REFERENCES`.
Файл создаётся пустым — заполняется по мере обнаружения несовместимостей.

### 4.3 Миграции структура

```
src/main/resources/db/
├── migration/           # PostgreSQL (prod)
│   └── V1__init_schema.sql
└── migration-h2/        # H2 патчи (local/test)
    └── V1_1__h2_compat.sql
```

---

## 5. Domain Model (Spring Data JDBC)

Spring Data JDBC — plain Java records, без аннотаций JPA.

```java
@Table("daily_plans")
public record DailyPlan(
    @Id Long id,
    LocalDate planDate,
    String summary,
    String status,
    Instant createdAt,
    Instant updatedAt,
    @MappedCollection(idColumn = "plan_id")
    Set<Task> tasks
) {}

@Table("tasks")
public record Task(
    @Id Long id,
    Long planId,
    String title,
    String description,
    String status,      // PENDING | TODO | IN_PROGRESS | DONE | BLOCKED | DELETED
    String priority,    // LOW | NORMAL | HIGH | CRITICAL
    LocalDate dueDate,
    String source,      // MANUAL | EMAIL | AGENT
    String emailId,     // заполняется если source=EMAIL
    Instant createdAt,
    Instant updatedAt
) {}

@Table("incidents")
public record Incident(
    @Id Long id,
    String title,
    String severity,
    String status,
    String description,
    String rootCause,
    String actionItems,
    Instant startedAt,
    Instant resolvedAt,
    Instant createdAt
) {}

@Table("risks")
public record Risk(
    @Id Long id,
    String title,
    String description,
    String probability,
    String impact,
    String status,
    String mitigation,
    Instant createdAt,
    Instant updatedAt
) {}

@Table("people")
public record Person(
    @Id Long id,
    String fullName,
    String login,
    String email,
    String phone,
    String domain,
    String currentTask,
    Integer capacitySprint,
    Integer capacityMonth,
    Integer capacityQuarter,
    String notes,
    Instant createdAt,
    Instant updatedAt
) {}

@Table("people_notes")
public record PeopleNote(
    @Id Long id,
    Long personId,
    String note,
    String tags,
    Instant createdAt
) {}
```

---

## 6. Repository Layer

```java
public interface DailyPlanRepository extends CrudRepository<DailyPlan, Long> {
    Optional<DailyPlan> findByPlanDate(LocalDate date);
}

public interface TaskRepository extends CrudRepository<Task, Long> {
    List<Task> findByPlanId(Long planId);
    List<Task> findByStatus(String status);          // для PENDING очереди
    List<Task> findByDueDate(LocalDate date);
}

public interface IncidentRepository extends CrudRepository<Incident, Long> {
    List<Incident> findByStatus(String status);
}

public interface RiskRepository extends CrudRepository<Risk, Long> {
    List<Risk> findByStatus(String status);
}

public interface PersonRepository extends CrudRepository<Person, Long> {
    List<Person> findByFullNameContainingIgnoreCase(String name);
}

public interface PeopleNoteRepository extends CrudRepository<PeopleNote, Long> {
    List<PeopleNote> findByPersonIdOrderByCreatedAtDesc(Long personId);
    List<PeopleNote> findTop10ByOrderByCreatedAtDesc();
}
```

---

## 7. Service Layer

```java
// Агрегирует данные для старта сессии агента
@Service
public class ContextService {
    public ContextDto buildContext() {
        // today plan + tasks (status != DELETED)
        // tomorrow plan + tasks
        // open incidents (status != RESOLVED)
        // open/high risks
        // recent people notes (last 10)
        // НЕ включает PENDING задачи — они отдельный поток
    }
}

// Бизнес-логика задач
@Service
public class TaskService {

    // Вызывается агентом через MCP после подтверждения пользователем
    public Task createConfirmed(LocalDate date, String title, String priority,
                                String description, String source, String emailId);

    // Вызывается java-mail-agent: кладёт задачу в очередь PENDING
    public Task createPending(String title, String description,
                              String emailId, String sender, String priority);

    // UI: подтвердить PENDING задачу → статус TODO
    public Task confirm(Long id);

    // UI: отклонить PENDING задачу → статус DELETED
    public Task reject(Long id);

    // UI или агент: редактировать перед подтверждением
    public Task edit(Long id, EditTaskRequest req);

    public Task markDone(Long id);
    public Task moveToDate(Long id, LocalDate toDate);
    public Task updateStatus(Long id, String status);
}
```

---

## 8. REST API

Base: `http://localhost:8082/api`

```
# Контекст
GET  /api/context                        # полный контекст для старта сессии агента

# Планы
GET  /api/plans?date=2026-06-08
POST /api/plans

# Задачи
GET  /api/tasks?date=2026-06-08&status=TODO
POST /api/tasks                          # создать подтверждённую задачу (агент после confirm)
PUT  /api/tasks/{id}
POST /api/tasks/{id}/done
POST /api/tasks/{id}/move                body: { "toDate": "2026-06-09" }

# Очередь подтверждения (PENDING)
GET  /api/tasks/pending                  # все задачи со статусом PENDING
POST /api/tasks/pending                  # создать PENDING задачу (вызывает mail-agent)
POST /api/tasks/{id}/confirm             # PENDING → TODO
POST /api/tasks/{id}/reject              # PENDING → DELETED

# Инциденты
GET  /api/incidents?status=OPEN
POST /api/incidents
PUT  /api/incidents/{id}
POST /api/incidents/{id}/resolve         body: { "rootCause": "...", "actionItems": "..." }

# Риски
GET  /api/risks?status=OPEN
POST /api/risks
PUT  /api/risks/{id}

# Люди
GET  /api/people
GET  /api/people?name=Иван
POST /api/people
PUT  /api/people/{id}
GET  /api/people/{id}/notes
POST /api/people/{id}/notes

# Health
GET  /actuator/health
```

---

## 9. Thymeleaf UI

Base: `http://localhost:8082/ui`

### Страницы

**`/ui/today`** — Главная страница

Секция **"Ожидают подтверждения"** (показывается только если есть PENDING задачи):
```
┌─────────────────────────────────────────────────┐
│ ⚠️ Ожидают подтверждения (2)                     │
│                                                  │
│ 📧 Письмо от ivanov@company.ru                   │
│    "Обсудить архитектуру payments"  HIGH         │
│    [Принять]  [Изменить]  [Отклонить]            │
│                                                  │
│ 📧 Письмо от pm@company.ru                       │
│    "Подготовить отчёт к пятнице"    NORMAL       │
│    [Принять]  [Изменить]  [Отклонить]            │
└─────────────────────────────────────────────────┘
```

Секция **"План на сегодня"**:
- Инлайн-редактирование title и priority прямо в строке
- Кнопки [Done] [Move →] [Delete] для каждой задачи
- Форма добавить задачу вручную внизу
- Редактируемый summary плана дня

Секция **"Завтра"**:
- Список задач на завтра (только просмотр + те же кнопки)

**`/ui/incidents`** — Активные инциденты
- Список с severity badge (P1/P2/P3)
- Кнопка [Edit] → форма редактирования inline
- Кнопка [Resolve] → модалка с полями root_cause и action_items
- Форма создать новый инцидент

**`/ui/risks`** — Карта рисков
- Таблица: title / probability / impact / status / mitigation
- Кнопка [Edit] inline
- Фильтр по статусу

**`/ui/people`** — Команда и стейкхолдеры
- Карточки людей: ФИО, логин, домен, текущая задача, capacity
- Кнопка [Edit] → форма редактирования карточки
- Хронологические заметки под каждой карточкой
- Форма добавить заметку

### Технические требования UI
- Bootstrap 5 CDN — никакого кастомного CSS
- Thymeleaf fragments: `fragments/layout.html` (nav + head)
- Формы через POST (не AJAX) — проще и надёжнее для MVP
- H2 Console на `/h2-console` в профиле `local`

---

## 10. MCP Server

**Зависимость:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
</dependency>
```

**Конфигурация:**
```properties
spring.ai.mcp.server.name=java-memory-service
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.sse-message-endpoint=/mcp/message
```

### Полный список MCP Tools

| Tool | Описание | Когда использует агент |
|------|----------|----------------------|
| `getContext` | Полный контекст сессии (today + tomorrow + incidents + risks + people notes) | Старт сессии |
| `getTasks` | Задачи на конкретную дату + фильтр по статусу | "Скажи план на сегодня" |
| `createTask` | Создать подтверждённую задачу (source=MANUAL/AGENT) | После явного "да" от пользователя |
| `markTaskDone` | Задача → DONE | "Отметь задачу X как выполненную" |
| `moveTask` | Перенести задачу на дату | "Перенеси задачу X на завтра" |
| `updateTaskStatus` | Изменить статус задачи | Любое изменение статуса |
| `createIncident` | Зафиксировать инцидент | После подтверждения пользователем |
| `resolveIncident` | Закрыть инцидент с root cause | После подтверждения пользователем |
| `addRisk` | Добавить риск | После подтверждения пользователем |
| `updateRisk` | Изменить статус/митигацию риска | После подтверждения пользователем |
| `addPeopleNote` | Записать заметку о человеке | Наблюдение по итогам встречи и т.д. |
| `searchPeople` | Найти человека по имени | "Что я знаю про Иванова?" |

### Правило подтверждения (ОБЯЗАТЕЛЬНО в CLAUDE.md агента)

Перед вызовом `createTask`, `createIncident`, `addRisk` агент ВСЕГДА показывает:

```
📌 Создать задачу?
   Название:  <title>
   Дата:      <date>
   Приоритет: <priority>
   Источник:  <source>

[да / нет / изменить]
```

И ждёт явного подтверждения: "да", "добавить", "confirm", "ок".

### Tool classes

```java
@Configuration
public class McpConfig {
    @Bean
    public ToolCallbackProvider memoryTools(...) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(
                new ContextTools(contextService),
                new TaskTools(taskService),
                new IncidentTools(incidentService),
                new RiskTools(riskService),
                new PeopleTools(peopleService)
            )
            .build();
    }
}

public class TaskTools {

    @Tool(description = "Get tasks for a specific date")
    public List<Task> getTasks(
        @ToolParam(description = "Date YYYY-MM-DD") String date,
        @ToolParam(description = "Status filter: TODO|IN_PROGRESS|DONE|BLOCKED", required = false) String status) { }

    @Tool(description = "Create a confirmed task. Call only after explicit user confirmation.")
    public Task createTask(
        @ToolParam(description = "Task title") String title,
        @ToolParam(description = "Date YYYY-MM-DD") String date,
        @ToolParam(description = "Priority: LOW|NORMAL|HIGH|CRITICAL", required = false) String priority,
        @ToolParam(description = "Description", required = false) String description,
        @ToolParam(description = "Source: MANUAL|AGENT") String source) { }

    @Tool(description = "Mark task as DONE")
    public Task markTaskDone(@ToolParam(description = "Task ID") Long id) { }

    @Tool(description = "Move task to another date")
    public Task moveTask(
        @ToolParam(description = "Task ID") Long id,
        @ToolParam(description = "Target date YYYY-MM-DD") String toDate) { }

    @Tool(description = "Update task status")
    public Task updateTaskStatus(
        @ToolParam(description = "Task ID") Long id,
        @ToolParam(description = "Status: TODO|IN_PROGRESS|DONE|BLOCKED") String status) { }
}
```

---

## 11. Интеграция с java-mail-agent

Когда mail-agent классифицировал письмо как `REQUEST` и извлёк задачу:

```
POST /api/tasks/pending
Content-Type: application/json

{
  "title":       "Обсудить архитектуру payments",
  "description": "Письмо от Иванова: нужно обсудить до пятницы",
  "emailId":     "<message-id из письма>",
  "sender":      "ivanov@company.ru",
  "priority":    "HIGH"
}
```

Задача создаётся со статусом `PENDING`.
Далее пользователь видит её в UI `/ui/today` в секции "Ожидают подтверждения" и нажимает [Принять] / [Изменить] / [Отклонить].

**Агент через MCP не участвует в этом потоке** — PENDING задачи подтверждаются только через UI.

---

## 12. Тесты

### Структура

```
src/test/java/ru/zaytsev/memory/
├── BaseMcpTest.java
├── repository/
│   ├── DailyPlanRepositoryTest.java
│   └── TaskRepositoryTest.java
├── service/
│   ├── ContextServiceTest.java
│   └── TaskServiceTest.java
├── api/
│   ├── TaskControllerTest.java
│   └── PendingTaskControllerTest.java
└── mcp/
    ├── McpConnectionTest.java       # SSE handshake + tools/list
    ├── McpContextToolTest.java      # getContext
    ├── McpTaskToolTest.java         # createTask, markDone, move
    └── McpIncidentToolTest.java     # createIncident, resolve
```

### Базовый класс MCP тестов

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseMcpTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String mcpMessageUrl() {
        return "http://localhost:" + port + "/mcp/message";
    }

    protected String mcpSseUrl() {
        return "http://localhost:" + port + "/mcp/sse";
    }

    protected ResponseEntity<String> callTool(String toolName, String argumentsJson) {
        String request = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "tools/call",
              "params": {
                "name": "%s",
                "arguments": %s
              }
            }
            """.formatted(toolName, argumentsJson);
        return restTemplate.postForEntity(mcpMessageUrl(), request, String.class);
    }
}
```

### MCP тесты

```java
class McpConnectionTest extends BaseMcpTest {

    @Test
    void toolsList_containsAllExpectedTools() {
        String request = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
            """;
        ResponseEntity<String> response = restTemplate.postForEntity(
            mcpMessageUrl(), request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
            .contains("getContext")
            .contains("getTasks")
            .contains("createTask")
            .contains("createIncident")
            .contains("addRisk");
    }
}

class McpTaskToolTest extends BaseMcpTest {

    @Test
    void createTask_thenVisibleInGetTasks() {
        String today = LocalDate.now().toString();

        // создаём задачу
        ResponseEntity<String> create = callTool("createTask", """
            {"title":"Провести 1-1","date":"%s","priority":"HIGH","source":"AGENT"}
            """.formatted(today));
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.OK);

        // проверяем что видна в getTasks
        ResponseEntity<String> tasks = callTool("getTasks", """
            {"date":"%s"}
            """.formatted(today));
        assertThat(tasks.getBody()).contains("Провести 1-1");
    }

    @Test
    void markTaskDone_changesStatus() { ... }

    @Test
    void moveTask_appearsOnTargetDate() { ... }
}

class McpContextToolTest extends BaseMcpTest {

    @Test
    void getContext_returnsTodayPlanAndOpenIncidents() {
        // GIVEN — данные через репозиторий
        // WHEN — getContext через MCP
        // THEN — today.tasks, open_incidents присутствуют
    }

    @Test
    void getContext_doesNotIncludePendingTasks() {
        // PENDING задачи не должны попадать в контекст агента
    }
}
```

---

## 13. Структура проекта

```
JavaMemoryService/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/ru/andreyz/memoryservice/
    │   │   ├── MemoryServiceApplication.java
    │   │   ├── domain/
    │   │   │   ├── DailyPlan.java
    │   │   │   ├── Task.java
    │   │   │   ├── Incident.java
    │   │   │   ├── Risk.java
    │   │   │   ├── Person.java
    │   │   │   └── PeopleNote.java
    │   │   ├── repository/
    │   │   │   ├── DailyPlanRepository.java
    │   │   │   ├── TaskRepository.java
    │   │   │   ├── IncidentRepository.java
    │   │   │   ├── RiskRepository.java
    │   │   │   ├── PersonRepository.java
    │   │   │   └── PeopleNoteRepository.java
    │   │   ├── service/
    │   │   │   ├── ContextService.java
    │   │   │   ├── TaskService.java
    │   │   │   ├── IncidentService.java
    │   │   │   ├── RiskService.java
    │   │   │   └── PeopleService.java
    │   │   ├── api/
    │   │   │   ├── ContextController.java
    │   │   │   ├── TaskController.java      # включает /pending endpoints
    │   │   │   ├── PlanController.java
    │   │   │   ├── IncidentController.java
    │   │   │   ├── RiskController.java
    │   │   │   └── PeopleController.java
    │   │   ├── ui/
    │   │   │   ├── TodayViewController.java
    │   │   │   ├── IncidentViewController.java
    │   │   │   ├── RiskViewController.java
    │   │   │   └── PeopleViewController.java
    │   │   ├── mcp/
    │   │   │   ├── McpConfig.java
    │   │   │   ├── ContextTools.java
    │   │   │   ├── TaskTools.java
    │   │   │   ├── IncidentTools.java
    │   │   │   ├── RiskTools.java
    │   │   │   └── PeopleTools.java
    │   │   └── dto/
    │   │       ├── ContextDto.java
    │   │       ├── CreateTaskRequest.java
    │   │       ├── CreatePendingTaskRequest.java
    │   │       ├── EditTaskRequest.java
    │   │       ├── MoveTaskRequest.java
    │   │       └── ResolveIncidentRequest.java
    │   └── resources/
    │       ├── application.properties
    │       ├── application-local.properties
    │       ├── application-prod.properties
    │       ├── db/
    │       │   ├── migration/
    │       │   │   └── V1__init_schema.sql
    │       │   └── migration-h2/
    │       │       └── V1_1__h2_compat.sql
    │       ├── templates/
    │       │   ├── fragments/layout.html
    │       │   ├── today.html
    │       │   ├── incidents.html
    │       │   ├── risks.html
    │       │   └── people.html
    │       └── static/style.css
    └── test/
        ├── java/ru/zaytsev/memory/
        │   ├── BaseMcpTest.java
        │   ├── repository/
        │   ├── service/
        │   ├── api/
        │   └── mcp/
        └── resources/
            └── application-test.properties
```

---

## 14. pom.xml (ключевые зависимости)

```xml
<groupId>ru.andreyz.memoryservice</groupId>
<artifactId>memory-service</artifactId>
<version>1.0.0-SNAPSHOT</version>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-thymeleaf</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 15. Интеграция с `.mcp.json`

```json
{
  "mcpServers": {
    "memory": {
      "url": "http://localhost:8082/mcp/sse",
      "transport": "sse"
    }
  }
}
```

---

## 16. Порядок реализации для Claude Code

1. `pom.xml` + `MemoryServiceApplication.java`
2. `application.properties` (все три профиля)
3. `V1__init_schema.sql` + `V1_1__h2_compat.sql`
4. Domain records
5. Repository interfaces
6. DTO классы
7. Service layer (сначала `TaskService` с PENDING логикой)
8. REST API controllers
9. MCP config + Tool classes
10. Thymeleaf templates (today.html с секцией PENDING — в первую очередь)
11. Тесты: repository → service → api → mcp

---

## 17. Известные нюансы Spring Data JDBC

- `@MappedCollection(idColumn = "plan_id")` — owned collection Task внутри DailyPlan
- При save DailyPlan Spring Data JDBC делает DELETE+INSERT для tasks → обновлять задачи через `TaskRepository` напрямую
- Records + Spring Boot 3 — конструктор определяется автоматически через `@PersistenceCreator`
- H2 MODE=PostgreSQL: если `TIMESTAMPTZ` не поддерживается — заменить на `TIMESTAMP` в `V1_1__h2_compat.sql`

---

## 18. Известные проблемы при сборке и запуске

### 1. Spring AI BOM версия не резолвится

**Симптом:** Maven не может скачать `spring-ai-bom:1.0.0` из Central.

**Решение:** попробовать версии в порядке приоритета:
```xml
<version>1.0.0-M6</version>
<!-- или -->
<version>1.0.0-RC1</version>
```

Также убедиться что подключён Spring milestone репозиторий:
```xml
<repositories>
    <repository>
        <id>spring-milestones</id>
        <url>https://repo.spring.io/milestone</url>
    </repository>
</repositories>
```

### 2. Flyway падает на TIMESTAMPTZ в H2

**Симптом:** при старте с профилем `local` или `test` Flyway бросает ошибку на `V1__init_schema.sql`.

**Решение:** добавить в `V1_1__h2_compat.sql` переопределение типов:
```sql
-- H2 не всегда принимает TIMESTAMPTZ даже в MODE=PostgreSQL
-- Этот файл применяется только для профилей local/test
ALTER TABLE daily_plans ALTER COLUMN created_at TIMESTAMP;
ALTER TABLE daily_plans ALTER COLUMN updated_at TIMESTAMP;
-- ... аналогично для всех таблиц
```

Либо сразу писать `TIMESTAMP` вместо `TIMESTAMPTZ` в основной миграции и не использовать `V1_1__h2_compat.sql`.

### 3. Spring Data JDBC + records + @PersistenceCreator

**Симптом:** ошибка маппинга при чтении из БД — Spring не может найти конструктор.

**Решение:** добавить аннотацию на конструктор record:
```java
@Table("tasks")
public record Task(
    @Id Long id,
    String title,
    // ...
) {
    @PersistenceCreator
    public Task { }  // compact constructor
}
```

### 4. Проверка MCP после запуска

После старта сервиса проверить что MCP работает и все tools зарегистрированы:

```bash
# tools/list — должен вернуть getContext, getTasks, createTask и др.
curl -X POST http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

# health check
curl http://localhost:8082/actuator/health

# H2 Console (только профиль local)
open http://localhost:8082/h2-console
```
