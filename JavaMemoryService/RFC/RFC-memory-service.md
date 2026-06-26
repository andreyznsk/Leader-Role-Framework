# RFC: java-memory-service

**Статус:** Living document
**Автор:** Андрей Зайцев
**Дата:** 2026-06-20
**Порт:** 8082

---

## Контекст системы
Перед работой с интеграциями читай `ARCHITECTURE.md`.

## 1. Назначение

`java-memory-service` — локальный Spring Boot 3 процесс (Java 21), который:

- Хранит оперативный контекст Tech Lead в PostgreSQL (local/prod) / H2 (test)
- Предоставляет Thymeleaf UI для просмотра и ручного редактирования данных
- Предоставляет MCP-интерфейс для Claude Agent (чтение/запись задач, планов, инцидентов)
- Принимает предложения задач от java-mail-agent (статус PENDING) и ждёт подтверждения через UI
- Принимает raw capture-заметки в `capture-inbox/`, пакетно классифицирует их через `AgentClient` из `common`
  и маршрутизирует в задачи, риски, заметки, вопросы, RAG inbox или daily journal
- Владеет usage statistics: пишет `usage_events` для AI-agent flows, task/capture сценариев,
  отдаёт агрегаты через `/api/stats/usage` и UI `/ui/stats`
- Даёт две UI-зоны: `Operational Memory` и `Knowledge Gateway`, не дублируя RAG-документы в своей БД
- Выступает единым control plane UI для runtime-редактирования plugin prompts через `/ui/settings`

---

## 2. Стек

| Компонент | Библиотека |
|-----------|-----------|
| Framework | Spring Boot 3.5.14 |
| Web | Spring MVC (embedded Tomcat) |
| UI | Thymeleaf + Bootstrap 5 CDN |
| Data | Spring Data JDBC (без Hibernate, без JPA) |
| Миграции | Flyway |
| DB local/prod | PostgreSQL (Docker/local) |
| DB test | H2 (in-memory, MODE=PostgreSQL) |
| JSON | Jackson (встроен в Boot) |
| MCP | spring-ai-starter-mcp-server-webmvc |
| LLM client | common 1.0.0 (`AgentClient`) |
| Тесты | JUnit 5, Spring Boot Test, MockMvc |
| Build | Maven, fat JAR через spring-boot-maven-plugin |

---

## 3. Профили

### `local` (default при разработке)
```yaml
# application-local.yml
spring:
  datasource:
    url: "jdbc:postgresql://172.80.2.1:5432/leader_framework?sslmode=disable"
    driver-class-name: org.postgresql.Driver
    username: memory_user
    password: memory_password
  flyway:
    locations: classpath:db/migration
    schemas: memory
    default-schema: memory

agent:
  provider: mock
```

### `prod`
```yaml
# application-prod.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/techlead
    username: techlead
    password: ${POSTGRES_MEMORY_PASSWORD}
    hikari:
      maximum-pool-size: 5
  flyway:
    locations: classpath:db/migration

agent:
  provider: ${AGENT_PROVIDER:claude}
```

### `e2e`
```yaml
# application-e2e.yml
agent:
  provider: mock
```

### `test`
```yaml
# src/test/resources/application-test.yml
spring:
  datasource:
    url: "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  flyway:
    locations: classpath:db/migration,classpath:db/migration-h2

app:
  capture:
    inbox-dir: ${java.io.tmpdir}/test-capture-inbox
  rag:
    inbox-dir: ${java.io.tmpdir}/test-rag-inbox
  workspace:
    dir: ${java.io.tmpdir}/test-workspace

agent:
  provider: mock
```

**Запуск** (из корня проекта `Leader-Role-Framework/`, как указано в ARCHITECTURE.md):
```bash
# local (PostgreSQL schema memory)
SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar

# prod (PostgreSQL)
SPRING_PROFILES_ACTIVE=prod java -jar JavaMemoryService/target/memory-service.jar
```

---

## 4. База данных

### 4.0 Prompt editing ideology

`JavaMemoryService` не хранит prompt templates внешних plugin-сервисов как master-data,
но даёт единый UI для их редактирования.

Архитектурное правило:

- prompt каждого plugin-а хранится в собственной БД plugin-а, в отдельной таблице;
- стартовое значение prompt создаётся Flyway migration-ом как seed data;
- `/ui/settings` показывает prompt как обычное descriptor field (`type=text`);
- сохранение идёт через control plane proxy в `PUT /api/control/settings` plugin-а;
- plugin сам сохраняет новый prompt в свою БД и использует его для следующих вызовов без рестарта;
- audit изменений prompt-а хранится в plugin audit и в proxy history.

Это позволяет править prompts в реальном времени без редактирования `.java` файлов и без redeploy.

### 4.0 Usage Statistics

Memory Service owns usage statistics. It records `usage_events` for AI-agent flows,
knowledge search, task creation, capture processing and task completion.

Core endpoints:
- `GET /api/stats/usage?period=today|7d|30d|all` — aggregated counters and saved time
- `GET /ui/stats` — Thymeleaf UI with period switcher, cards, sources, formula and latest events
- `POST /api/stats/events` — local-profile debug endpoint for e2e/manual event injection
- `POST /api/knowledge/search` — Memory-owned proxy to JavaRagService `/api/search` with
  `KNOWLEDGE_SEARCH`, `RAG_SEARCH` and `RAG_RESULT_USED` usage events
- `GET /api/knowledge/documents`, `GET/PUT /api/knowledge/documents/{id}`, `POST /api/knowledge/documents/{id}/reindex`
  — browser-facing proxy управления RAG-документами

Saved time MVP formula:
- `ASK_ANSWERED = 15 min`
- `RAG_RESULT_USED = 10 min`
- `MAIL_TASK_CREATED = 3 min`
- `CAPTURE_PROCESSED = 2 min`
- `TASK_CREATED = 1 min`

Для mail-derived задач `usage_events.correlation_id` должен допускать длинные `Message-ID`, поэтому хранится без ограничения `VARCHAR(128)`.

### 4.1 Схема (V1__init_schema.sql)

```sql
-- Ежедневные планы
CREATE TABLE daily_plans (
    id         BIGSERIAL PRIMARY KEY,
    plan_date  DATE         NOT NULL UNIQUE,
    summary    TEXT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | DONE | CANCELLED
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
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
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Инциденты
CREATE TABLE incidents (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(500) NOT NULL,
    severity     VARCHAR(10)  NOT NULL,                  -- P1 | P2 | P3
    status       VARCHAR(20)  NOT NULL DEFAULT 'OPEN',   -- OPEN | INVESTIGATING | RESOLVED | CLOSED
    description  TEXT,
    root_cause   TEXT,
    action_items TEXT,
    started_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    resolved_at  TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
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
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
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
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Хронологические заметки по людям
CREATE TABLE people_notes (
    id          BIGSERIAL PRIMARY KEY,
    person_id   BIGINT       REFERENCES people(id) ON DELETE CASCADE,
    note        TEXT         NOT NULL,
    tags        VARCHAR(500),   -- через запятую: trust,blocker,key-person
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Состояние входящей почты
CREATE TABLE email_state (
    id             BIGSERIAL PRIMARY KEY,
    message_id     VARCHAR(500) UNIQUE NOT NULL,
    subject        VARCHAR(1000),
    sender         VARCHAR(500),
    received_at    TIMESTAMP,
    classification VARCHAR(20),   -- DRAFT | REQUEST | NOISE | CAPTURE | NOTICE
    status         VARCHAR(20)  NOT NULL DEFAULT 'NEW',  -- NEW | PROCESSED | IGNORED
    summary        TEXT,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
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

H2 используется только в тестах. `application-test.yml` включает `MODE=PostgreSQL`
и `CASE_INSENSITIVE_IDENTIFIERS=TRUE`; файл `V1_1__h2_compat.sql` остаётся
точкой для H2-only патчей.

### 4.3 Миграции структура

```
src/main/resources/db/
├── migration/           # PostgreSQL (prod)
│   ├── V1__init_schema.sql
│   ├── V2__add_capture_tables.sql
│   ├── V3__add_task_sort_order.sql
│   └── V4__add_notes_and_capture.sql
└── migration-h2/        # H2 патчи (local/test)
    └── V1_1__h2_compat.sql
```

`V3__add_task_sort_order.sql`:
```sql
ALTER TABLE tasks ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
CREATE INDEX idx_tasks_sort_order ON tasks(plan_id, sort_order);
```

При создании задачи `sort_order = MAX(sort_order) + 1` в рамках `plan_id`.

`V2__add_capture_tables.sql`:
```sql
CREATE TABLE captures (
    id           BIGSERIAL PRIMARY KEY,
    raw_text     TEXT        NOT NULL,
    source       VARCHAR(50) NOT NULL DEFAULT 'cli',
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    classified   VARCHAR(20),
    routed_to    VARCHAR(100),
    captured_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP
);

CREATE TABLE questions (
    id         BIGSERIAL PRIMARY KEY,
    title      VARCHAR(500) NOT NULL,
    context    TEXT,
    status     VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE person_notes (
    id          BIGSERIAL PRIMARY KEY,
    person_name VARCHAR(100) NOT NULL,
    note        TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
```

`V4__add_notes_and_capture.sql`:
```sql
CREATE TABLE notes (
    id         BIGSERIAL PRIMARY KEY,
    text       TEXT         NOT NULL,
    tags       VARCHAR(500),
    source     VARCHAR(50)  NOT NULL DEFAULT 'agent',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
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
    Integer sortOrder,  // порядок в списке плана (CR-MEM-003)
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

@Table("captures")
public record Capture(
    @Id Long id,
    String rawText,
    String source,
    String status,      // PENDING | PROCESSED
    String classified,  // TASK | RISK | NOTE | PERSON_NOTE | KNOWLEDGE | JOURNAL | QUESTION
    String routedTo,
    Instant capturedAt,
    Instant processedAt
) {}

@Table("notes")
public record Note(
    @Id Long id,
    String text,
    String tags,
    String source,
    Instant createdAt
) {}

@Table("questions")
public record Question(
    @Id Long id,
    String title,
    String context,
    String status,
    Instant createdAt
) {}

@Table("person_notes")
public record PersonNameNote(
    @Id Long id,
    String personName,
    String note,
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
    List<Task> findByPlanIdOrderBySortOrder(Long planId);
    List<Task> findByPlanIdAndStatusNotOrderBySortOrder(Long planId, String status);
    List<Task> findByStatus(String status);          // для PENDING очереди
    List<Task> findByDueDate(LocalDate date);
    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM tasks WHERE plan_id = :planId")
    int findMaxSortOrderByPlanId(Long planId);
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

public interface CaptureRepository extends CrudRepository<Capture, Long> {
    List<Capture> findByStatus(String status);
    List<Capture> findByStatusAndDay(String status, Instant from, Instant to);
    List<Capture> findByDay(Instant from, Instant to);
    List<Capture> findRecent();
}

public interface NoteRepository extends CrudRepository<Note, Long> {
    List<Note> findTop200ByOrderByCreatedAtDesc();
}

public interface QuestionRepository extends CrudRepository<Question, Long> {
    List<Question> findByStatus(String status);
}

public interface PersonNameNoteRepository extends CrudRepository<PersonNameNote, Long> {
    List<PersonNameNote> findByPersonNameIgnoreCase(String personName);
}
```

---

## 7. Service Layer

```java
// Агрегирует данные для старта сессии агента
@Service
public class ContextService {
    public ContextDto buildContext() {
        // today plan + tasks (status != DELETED && status != PENDING)
        // tomorrow plan + tasks (status != DELETED && status != PENDING)
        // open incidents (status == OPEN)
        // open risks (status == OPEN)
        // recent people notes (last 10)
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
    public Task reorder(Long id, String direction, Integer position);
    public List<Task> findByDate(LocalDate date); // без DELETED, с сортировкой sort_order
}

@Service
public class CaptureProcessingService {
    // Берёт файлы capture-inbox/YYYY-MM-DD/*.md, классифицирует батчем,
    // маршрутизирует и переносит успешно обработанные файлы в processed/YYYY-MM-DD/.
    public ProcessResult processToday();
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
POST /api/tasks                          # создать подтверждённую задачу; поддерживает title, description, priority, status, dueDate, date, source
PUT  /api/tasks/{id}
PATCH /api/tasks/{id}/status             body: { "status": "TODO|IN_PROGRESS|BLOCKED|DONE" }
POST /api/tasks/{id}/done
POST /api/tasks/{id}/move                body: { "toDate": "2026-06-09" }
POST /api/tasks/{id}/reorder             body: { "direction": "up"|"down" } | { "position": N }
POST   /api/tasks/{id}/delete            # мягкое удаление (статус → DELETED) + удалить workspace/tasks/TASK-{id}.md если файл существует
DELETE /api/tasks/{id}                   # мягкое удаление (статус → DELETED) + удалить workspace/tasks/TASK-{id}.md, REST-алиас

# Knowledge Gateway proxy (без прямого JDBC в schema rag)
POST /api/knowledge/search
GET  /api/knowledge/documents
GET  /api/knowledge/documents/{id}
PUT  /api/knowledge/documents/{id}
POST /api/knowledge/documents/{id}/reindex
GET  /api/notices                       # legacy alias/filter type=NOTICE

# Описания задач (файловая шина workspace/tasks/)
GET  /api/tasks/{id}/description         # 200 text/plain | 204 если файл отсутствует
PUT  /api/tasks/{id}/description         body: text/plain → записать в файл

# Очередь подтверждения (PENDING)
GET  /api/tasks/pending                  # все задачи со статусом PENDING
POST /api/tasks/pending                  # создать PENDING задачу (вызывает mail-agent)
POST /api/tasks/{id}/confirm             # PENDING → TODO
POST /api/tasks/{id}/reject              # PENDING → DELETED

# Инциденты
GET  /api/incidents?status=OPEN
GET  /api/incidents/{id}
POST /api/incidents
PUT  /api/incidents/{id}
DELETE /api/incidents/{id}               # soft delete: status → CLOSED
POST /api/incidents/{id}/resolve         body: { "rootCause": "...", "actionItems": "..." }

# Риски
GET  /api/risks?status=OPEN
GET  /api/risks/{id}
POST /api/risks
PUT  /api/risks/{id}
DELETE /api/risks/{id}                   # soft delete: status → CLOSED

# Люди
GET  /api/people
GET  /api/people?name=Иван
GET  /api/people/{id}
POST /api/people
PUT  /api/people/{id}
DELETE /api/people/{id}                  # hard delete, people_notes cascade
GET  /api/people/{id}/notes
POST /api/people/{id}/notes
GET  /api/people/name/{name}/notes
POST /api/people/name/{name}/notes

# Capture Bot
POST /api/capture                        # 200, сохранить raw заметку в БД + capture-inbox
GET  /api/capture/today
GET  /api/capture/recent                 # последние 20
POST /api/capture/process-today          # batch classify + route
POST /api/capture/process-now            # alias process-today

# Notes / Questions
GET  /api/notes?tags=risk,person&limit=50
POST /api/notes
GET  /api/questions?status=OPEN
POST /api/questions

# Health
GET  /actuator/health
```

---

## 9. Thymeleaf UI

Base: `http://localhost:8082/ui`

### Страницы

**`/ui/today`** — Главная страница

Секции страницы (порядок сверху вниз):
1. **Сводка** — 4 карточки: задач сегодня / ожидают подтверждения / открытые инциденты / выполнено
2. **Ожидают подтверждения** — показывается только если есть PENDING задачи
3. **Текущие задачи** — список активных задач с фильтрами и сортировкой по полям

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

Секция **"Текущие задачи"**:

- фильтры: `priority`, `status`, `dueDate`
- сортировка кнопками `↑/↓` у полей `priority`, `status`, `dueDate`
- задача открывается по клику на название
- дедлайн показывается отдельной колонкой `DL`
- кнопка `Завтра` сдвигает дедлайн на `+1 день` относительно текущего дедлайна
- удаление происходит через modal-подтверждение и удаляет также markdown-файл описания

Управление задачами в строке:

| Элемент | Действие | HTTP |
|---------|----------|------|
| Чекбокс | `DONE` / снять | `POST /api/tasks/{id}/done` |
| Иконка флага | циклически менять приоритет | `PUT /api/tasks/{id}` |
| Название задачи | открыть форму редактирования | `GET /ui/tasks/{id}/edit` |
| Кнопка `Завтра` | сдвинуть дедлайн на +1 день | `PUT /api/tasks/{id}` |
| Иконка удаления | открыть modal подтверждения и удалить задачу + md-файл | `DELETE /api/tasks/{id}` |

Добавление задачи: зелёная кнопка `Добавить / задачу` расположена рядом с `Применить` и `Сбросить`, открывает modal-форму с полями `title`, `description`, `priority`, `status`, `dueDate`. После `POST /api/tasks` описание дополнительно пишется в `workspace/tasks/TASK-{id}.md` через `PUT /api/tasks/{id}/description`.

**`/ui/tasks/{id}/edit`** — Форма редактирования задачи

| Поле | Тип | Источник данных |
|------|-----|----------------|
| `title` | text input | `tasks.title` (PostgreSQL) |
| `description` | Markdown textarea | `workspace/tasks/TASK-{id}.md` (файловая шина) |
| `priority` | select | `tasks.priority` |
| `due_date` | date input | `tasks.due_date` |
| `status` | radio-пилюли | `tasks.status` |
| `source` | readonly badge | `tasks.source` + `tasks.email_id` |

Markdown-редактор — две вкладки: `markdown` (raw, monospace) и `preview` (HTML-рендер через JS, без библиотек).
Под textarea — бейдж с путём к файлу: `📄 workspace/tasks/TASK-003.md`.

Удаление на странице edit — двойное подтверждение: первый клик меняет текст на "точно удалить?" (3 сек), второй — `DELETE /api/tasks/{id}` + удаление файла описания.

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

**`/ui/notes`** — Лента заметок
- Список notes из PostgreSQL, сортировка от новых к старым
- Фильтр по тегам через `GET /api/notes?tags=...`
- Кнопка "в задачу" создаёт PENDING задачу через `POST /api/tasks/pending`
- Operational Notes: это не RAG knowledge и не source of truth для `NOTICE`

**`/ui/captures`** — Capture Inbox
- raw captures и их routing state
- кнопка `Process Now` вызывает `POST /api/capture/process-now`

**`/ui/knowledge`** — RAG Knowledge / Knowledge Gateway
- список документов из JavaRagService REST API
- фильтры по типам (`NOTICE`, `ADR`, `PROCESS`, `SERVICE_CARD`, `GLOSSARY`)
- edit/reindex без JDBC-доступа к схеме `rag`

**`/ui/notice`**
- не самостоятельный экран
- redirect на `/ui/knowledge?type=NOTICE`

**Capture UI / ручной запуск**
- Capture сохраняется через REST `POST /api/capture`
- Ручной запуск обработки: `POST /api/capture/process-now`
- Плановый запуск: `CaptureScheduler` по cron `capture.scheduler.cron`

### Технические требования UI
- Bootstrap 5 CDN + `static/style.css`
- Thymeleaf fragments: `fragments/layout.html` (nav + head)
- Формы через POST (не AJAX) — проще и надёжнее для MVP
- Для PUT из HTML-форм включён `spring.mvc.hiddenmethod.filter.enabled=true`
  и используется hidden input `_method=PUT`

---

## 10. MCP Server

**Зависимость:**
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

**Конфигурация:**
```properties
spring.ai.mcp.server.name=java-memory-service
spring.ai.mcp.server.version=1.0.0
spring.ai.mcp.server.type=SYNC
spring.ai.mcp.server.sse-endpoint=/mcp/sse
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
| `getTaskDescription` | Читать Markdown-описание задачи из файловой шины | "Покажи детали задачи X" |
| `setTaskDescription` | Записать Markdown-описание задачи в файловую шину | "Обнови детали задачи X" |
| `createIncident` | Зафиксировать инцидент | После подтверждения пользователем |
| `resolveIncident` | Закрыть инцидент с root cause | После подтверждения пользователем |
| `addRisk` | Добавить риск | После подтверждения пользователем |
| `updateRisk` | Изменить статус/митигацию риска | После подтверждения пользователем |
| `addPeopleNote` | Записать заметку о человеке | Наблюдение по итогам встречи и т.д. |
| `searchPeople` | Найти человека по имени | "Что я знаю про Иванова?" |

`getTaskDescription` реализован поверх `GET /api/tasks/{id}/description`; файл хранится в `workspace/tasks/TASK-{id}.md`.

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

    @Tool(description = "Get task description from file bus. Returns empty string if no file.")
    public String getTaskDescription(
        @ToolParam(description = "Task ID") Long id) { }

    @Tool(description = "Write or update task description in file bus.")
    public void setTaskDescription(
        @ToolParam(description = "Task ID") Long id,
        @ToolParam(description = "Markdown content") String content) { }
}
```

---

## 11. Файловая шина описаний задач

Описания задач хранятся вне PostgreSQL — в файловой системе:

```
Leader-Role-Framework/
└── workspace/
    └── tasks/
        ├── TASK-001.md
        ├── TASK-002.md
        └── TASK-003.md
```

Формат имени файла: `TASK-` + id задачи с нулями до 3 знаков + `.md`.

**Формат файла** — свободный Markdown, агент пишет в произвольном формате.
Рекомендуемая структура (не обязательная):

```markdown
## Контекст
...

## Что нужно сделать
- пункт 1

## Дедлайн
...
```

**`TaskFileService`** — создаёт директорию при старте (`@PostConstruct`), читает/пишет файлы через `Files.readString` / `Files.writeString`. Если файл отсутствует при чтении — возвращает пустую строку (нормально для старых задач).

При удалении задачи через `POST /api/tasks/{id}/delete` или `DELETE /api/tasks/{id}` файл описания удаляется (`Files.deleteIfExists`).

---

## 12. Capture Bot

Capture Bot реализует поток `capture now, classify later`.

### Приём заметки

```
POST /api/capture
Content-Type: application/json

{
  "text": "Риск: только один человек знает деплой",
  "source": "manual"
}
```

Ответ текущего контроллера:
```json
{
  "file": "capture-inbox/2026-06-12/14-32-07.md",
  "saved": true,
  "captureId": 42,
  "savedAt": "2026-06-12T07:32:07Z"
}
```

HTTP status: `200 OK`. Сохранение создаёт запись `captures(status=PENDING)` и файл:

```
capture-inbox/YYYY-MM-DD/HH-MM-SS.md
```

Формат файла:
```markdown
---
date: YYYY-MM-DD HH:MM:SS
source: manual
---
<raw text>
```

Если имя занято, добавляется суффикс `-1`, `-2` и т.д.

### Обработка

`CaptureScheduler` запускает `CaptureProcessingService.processToday()` по cron:

```yaml
capture:
  scheduler:
    cron: "0 0 * * * *"
```

Также доступны ручные endpoints:

```
POST /api/capture/process-today
POST /api/capture/process-now
```

Алгоритм:

1. Прочитать `capture-inbox/YYYY-MM-DD/*.md`.
2. Построить day context через `ContextService`: текущие задачи и открытые риски.
3. Передать весь батч в `CaptureClassifierAgent`, который вызывает `agentClient.complete(prompt)`.
4. Получить JSON-массив `ClassifiedCapture`.
5. Для каждого элемента вызвать `CaptureRouter`.
6. Если route успешен, перенести файл в `capture-inbox/processed/YYYY-MM-DD/`.
7. Если route упал, файл остаётся в очереди и попадёт в следующий запуск.

`application-e2e.yml` включает `agent.provider=mock`: вместо реального LLM
используется `MockAgentClient` из `common`, который классифицирует по явным маркерам
`TASK:`, `RISK:`, `NOTE:`, `QUESTION:`, `PERSON_NOTE:`, `KNOWLEDGE:`, `JOURNAL:`.

### Маршрутизация

| Type | Действие |
|------|----------|
| `TASK` | `TaskService.createPending(...)`, статус `PENDING`, подтверждение в `/ui/today` |
| `RISK` | `RiskService.create(...)`, probability/impact по умолчанию `MEDIUM` |
| `NOTE` | `NoteService.create(..., source="capture")` |
| `QUESTION` | `QuestionService.create(...)` |
| `PERSON_NOTE` | `person_notes` по имени через `PersonNameNoteRepository` |
| `KNOWLEDGE` | Markdown-файл в `${app.rag.inbox-dir}/captures/` |
| `JOURNAL` | append в `${app.workspace.dir}/08_daily_journal/YYYY-MM-DD.md` |

---

## 13. Интеграция с java-mail-agent

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
`NOTICE` письма в этот поток не попадают: они сразу становятся RAG-документами в Knowledge Gateway.

---

## 14. Тесты

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

## 15. Структура проекта

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
    │   │   │   ├── PeopleNote.java
    │   │   │   ├── Capture.java
    │   │   │   ├── Note.java
    │   │   │   ├── Question.java
    │   │   │   └── PersonNameNote.java
    │   │   ├── repository/
    │   │   │   ├── DailyPlanRepository.java
    │   │   │   ├── TaskRepository.java
    │   │   │   ├── IncidentRepository.java
    │   │   │   ├── RiskRepository.java
    │   │   │   ├── PersonRepository.java
    │   │   │   ├── PeopleNoteRepository.java
    │   │   │   ├── CaptureRepository.java
    │   │   │   ├── NoteRepository.java
    │   │   │   ├── QuestionRepository.java
    │   │   │   └── PersonNameNoteRepository.java
    │   │   ├── service/
    │   │   │   ├── ContextService.java
    │   │   │   ├── TaskService.java
    │   │   │   ├── TaskFileService.java            # read/write workspace/tasks/TASK-{id}.md
    │   │   │   ├── IncidentService.java
    │   │   │   ├── RiskService.java
    │   │   │   ├── PeopleService.java
    │   │   │   ├── CaptureService.java
    │   │   │   ├── CaptureProcessingService.java
    │   │   │   ├── CaptureClassifierAgent.java    # inject AgentClient из common
    │   │   │   ├── CaptureRouter.java
    │   │   │   ├── CaptureScheduler.java
    │   │   │   ├── NoteService.java
    │   │   │   └── QuestionService.java
    │   │   ├── api/
    │   │   │   ├── ContextController.java
    │   │   │   ├── TaskController.java          # включает /pending, /reorder, /delete endpoints
    │   │   │   ├── TaskDescriptionController.java  # GET/PUT /api/tasks/{id}/description
    │   │   │   ├── PlanController.java
    │   │   │   ├── IncidentController.java
    │   │   │   ├── RiskController.java
    │   │   │   ├── PeopleController.java
    │   │   │   ├── CaptureController.java
    │   │   │   ├── NoteController.java
    │   │   │   └── QuestionController.java
    │   │   ├── ui/
    │   │   │   ├── TodayViewController.java
    │   │   │   ├── TaskEditController.java         # GET/POST /ui/tasks/{id}/edit
    │   │   │   ├── IncidentViewController.java
    │   │   │   ├── RiskViewController.java
    │   │   │   ├── PeopleViewController.java
    │   │   │   └── NotesViewController.java
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
    │   │       ├── ReorderTaskRequest.java          # { direction, position }
    │   │       ├── ResolveIncidentRequest.java
    │   │       ├── CaptureRequest.java
    │   │       ├── CaptureResponse.java
    │   │       ├── ClassifiedCapture.java
    │   │       ├── CreateNoteRequest.java
    │   │       └── UpdateTaskStatusRequest.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml
    │       ├── application-prod.yml
    │       ├── application-e2e.yml
    │       ├── db/
    │       │   ├── migration/
    │       │   │   ├── V1__init_schema.sql
    │       │   │   ├── V2__add_capture_tables.sql
    │       │   │   ├── V3__add_task_sort_order.sql
    │       │   │   └── V4__add_notes_and_capture.sql
    │       │   └── migration-h2/
    │       │       └── V1_1__h2_compat.sql
    │       ├── templates/
    │       │   ├── fragments/layout.html
    │       │   ├── today.html
    │       │   ├── task-edit.html                  # форма редактирования с MD-редактором
    │       │   ├── incidents.html
    │       │   ├── risks.html
    │       │   ├── people.html
    │       │   └── notes.html
    │       └── static/
    └── test/
        ├── java/ru/andreyz/memoryservice/
        │   ├── service/
        │   ├── api/
        │   ├── ui/
        │   └── mcp/
        └── resources/
            └── application-test.yml
```

---

## 16. pom.xml (ключевые зависимости)

```xml
<groupId>ru.andreyz.memoryservice</groupId>
<artifactId>memory-service</artifactId>
<version>1.0.0-SNAPSHOT</version>

<parent>
    <groupId>ru.andreyz</groupId>
    <artifactId>leader-role-framework</artifactId>
    <version>1.0.0</version>
    <relativePath>../pom.xml</relativePath>
</parent>

<dependencies>
    <dependency>
        <groupId>ru.andreyz</groupId>
        <artifactId>common</artifactId>
    </dependency>
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
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
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
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

```

---

## 17. Интеграция с `.mcp.json`

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

## 18. Порядок реализации для Claude Code

**Базовый сервис:**
1. `pom.xml` + `MemoryServiceApplication.java`
2. `application.yml` + профили `local`, `prod`, `test`, `e2e`
3. `V1__init_schema.sql` + `V2__add_capture_tables.sql` + `V3__add_task_sort_order.sql` + `V4__add_notes_and_capture.sql`
4. Domain records
5. Repository interfaces
6. DTO классы
7. Service layer (сначала `TaskService` с PENDING логикой)
8. REST API controllers
9. MCP config + Tool classes
10. Thymeleaf templates (today.html с секцией PENDING — в первую очередь)
11. Тесты: repository → service → api → mcp

**CR-MEM-003 (UI Task Manager):**
12. `V3__add_task_sort_order.sql` — миграция поля сортировки
13. `TaskFileService` + `workspace/tasks/` инициализация
14. `TaskDescriptionController` (GET/PUT `/api/tasks/{id}/description`)
15. Reorder endpoint в `TaskController` + `ReorderTaskRequest`
16. `TaskEditController` + `task-edit.html` (форма с MD-редактором)
17. Обновить `today.html`: sort_order, drag handle, стрелки, иконки карандаш/корзина, inline add
18. `getTaskDescription` MCP tool в `TaskTools`

**CR-MEM-001/002 (Capture Bot):**
19. `CaptureController`, `CaptureService`, `CaptureRepository`
20. `CaptureClassifierAgent` + `common.MockAgentClient` (`MockCaptureClassifierAgent` удалён)
21. `CaptureProcessingService`, `CaptureRouter`, `CaptureScheduler`
22. `NoteController`, `NoteService`, `QuestionService`, `PersonNameNoteRepository`
23. `notes.html` + сценарии `10_capture_bot.md`, `11_capture_bot_improvements.md`, `12_capture_classification_mock.md`

---

## 19. Известные нюансы Spring Data JDBC

- `@MappedCollection(idColumn = "plan_id")` — owned collection Task внутри DailyPlan
- При save DailyPlan Spring Data JDBC делает DELETE+INSERT для tasks → обновлять задачи через `TaskRepository` напрямую
- Records + Spring Boot 3 — конструктор определяется автоматически через `@PersistenceCreator`
- H2 MODE=PostgreSQL используется только в тестовом профиле; основные миграции используют `TIMESTAMP`

---

## 20. Известные проблемы при сборке и запуске

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

### 2. H2 тесты и регистр идентификаторов

**Симптом:** тесты на H2 не находят таблицы/колонки, хотя Flyway применил миграции.

**Решение:** тестовый профиль должен включать:
```yaml
spring:
  datasource:
    url: "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
```

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

# capture health smoke
curl http://localhost:8082/api/capture/recent
```
