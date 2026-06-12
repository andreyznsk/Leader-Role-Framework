# LeaderOS — Architecture - Мастер-Спека

**Последнее обновление:** 2026-06-12  
**Статус:** Living document — обновлять при любом изменении контрактов между сервисами

---

## Обзор системы

AI-powered фреймворк техлида. Автоматизирует рутину: обработку почты,
ведение плана дня, работу с документацией, мониторинг команды.

```
┌─────────────────────────────────────────────────────────────────┐
│                          LeaderOS                                │
│                                                                  │
│  ┌─────────────────┐        ┌──────────────────────────────┐    │
│  │  JavaMailAgent  │──────→ │      JavaMemoryService       │    │
│  │  :8080          │  HTTP  │      :8082                   │    │
│  │  schema:        │        │  schema: memory              │    │
│  │  mailagent      │        │  PostgreSQL (Docker :5432)   │    │
│  └────────┬────────┘        └──────────────────────────────┘    │
│           │ запускает                                            │
│           ↓                                                      │
│  ┌─────────────────┐        ┌──────────────────────────────┐    │
│  │  claude --print │        │      JavaRagService          │    │
│  │  (Claude Code)  │        │      :8081                   │    │
│  └─────────────────┘        │  OpenSearch (Docker :9200)   │    │
│                              │  Ollama (local :11434)       │    │
│  ┌─────────────────┐        └──────────────────────────────┘    │
│  │  Maildev        │                                             │
│  │  Docker :1080   │   ← только local окружение                 │
│  │  SMTP   :1025   │                                             │
│  └─────────────────┘                                             │
└─────────────────────────────────────────────────────────────────┘
```

---

## Сервисы

### JavaMailAgent
**Порт:** 8080  
**Тип:** Spring Boot 3, fat-jar, запускается из корня проекта  
**RFC:** `JavaMailAgent/RFC-java-core.md`  
**Статус:** In Progress

**Роль:** Читает входящую почту, запускает Claude-агента на каждое письмо,
выполняет детерминированное действие по результату классификации.

**База данных:** PostgreSQL, схема `mailagent`, владелец `mailagent_user`  
**Миграции:** Flyway, `classpath:db/migration`, только схема `mailagent`

**Протоколы подключения к почте:**
| Профиль | Протокол | Клиент |
|---------|----------|--------|
| local | Maildev HTTP API | `MaildevClient` |
| dev | IMAP | `ImapMailClient` |
| prod | EWS (Exchange on-premise) | `EwsMailClient` |

**Классификация писем (enum AgentResponseType):**
| Тип | Действие |
|-----|----------|
| `REQUEST` | Добавить в `plans/today.md` + POST `/api/tasks/pending` в JavaMemoryService |
| `DRAFT` | Сохранить черновик в `drafts/` |
| `NOISE` | Пометить прочитанным на сервере, переместить в `processed/` |

**Трекинг обработанных писем:** таблица `mailagent.processed_emails`
- `REQUEST` и `DRAFT` — письмо остаётся непрочитанным на сервере
- `NOISE` — помечается прочитанным на сервере

**Исходящие вызовы:**
- `POST http://localhost:8082/api/tasks/pending` — создать PENDING задачу

**UI:** `http://localhost:8080/ui/status` — статус агента, счётчики, последние логи

---

### JavaMemoryService
**Порт:** 8082  
**Тип:** Spring Boot 3, fat-jar  
**RFC:** `JavaMemoryService/RFC-memory-service.md` *(создать)*  
**Статус:** In Progress

**Роль:** Операционная память техлида. Хранит быстро меняющиеся данные —
задачи, планы, инциденты, состояние писем.

**База данных:** PostgreSQL, схема `memory`, владелец `memory_user`

**Схемы PostgreSQL:**
```
БД: leader_framework
├── schema: mailagent   ← JavaMailAgent   (owner: mailagent_user)
└── schema: memory      ← JavaMemoryService (owner: memory_user)
```
Каждый сервис управляет только своей схемой через Flyway.  
Инициализация: `infra/postgres/init.sql` — схемы, пользователи, права.

**Ключевые endpoint-ы:**
| Метод | Путь | Описание |
|-------|------|----------|
| `POST` | `/api/tasks/pending` | Создать задачу со статусом PENDING |
| `GET` | `/api/tasks/today` | Задачи на сегодня |
| `PATCH` | `/api/tasks/{id}/status` | Изменить статус задачи |
| `GET` | `/ui/today` | Web UI: план дня |

**Статусы задачи:** `PENDING → TODO → IN_PROGRESS → DONE` / `DELETED`

**Входящие вызовы от:** JavaMailAgent

---

### JavaRagService
**Порт:** 8081  
**Тип:** Spring Boot 3 / lightweight Java, fat-jar  
**RFC:** `JavaRagService/RFC-rag-service.md` *(создать)*  
**Статус:** In Progress

**Роль:** RAG (Retrieval-Augmented Generation) — поиск по базе знаний команды.

**Зависимости:**
- OpenSearch (Docker) — векторное хранилище, порт 9200
- Ollama (локально) — эмбеддинги через `multilingual-e5-large`, порт 11434

**Ключевые endpoint-ы:**
| Метод | Путь | Описание |
|-------|------|----------|
| `POST` | `/api/search` | Векторный поиск по запросу |
| `POST` | `/api/index` | Индексировать документ |

**Входящие вызовы от:** Claude-агент (через MCP или напрямую)

---

## Инфраструктура (Docker)

```yaml
services:
  postgres:
    image: postgres:16
    ports: ["5432:5432"]
    volumes:
      - ./infra/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    # используется: JavaMailAgent (schema: mailagent)
    #               JavaMemoryService (schema: memory)

  opensearch:
    image: opensearchproject/opensearch:2
    ports: ["9200:9200"]

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2
    ports: ["5601:5601"]

  maildev:
    image: maildev/maildev:latest
    ports:
      - "1080:1080"
      - "1025:1025"
    profiles: [local]
```

**Ollama** — нативно на macOS (Apple Silicon M1), не в Docker.  
Модель эмбеддингов: `multilingual-e5-large`

---

## Файловая шина

```
Leader-Role-Framework/
├── inbox/              ← JavaMailAgent пишет входящие письма (JSON)
├── processed/          ← письма после обработки
├── drafts/             ← черновики ответов от агента
├── plans/
│   └── today.md        ← план дня
├── capture-inbox/      ← Capture Bot складывает сырые заметки (NEW)
│   └── YYYY-MM-DD/
│       └── HH-MM-SS.md
├── workspace/
│   └── tasks/          ← файлы задач по id (NEW)
│       ├── TASK-001.md
│       └── TASK-002.md
└── cr/                 ← CR для ARCHITECTURE.md и CLAUDE.md (NEW)
    ├── CR-ARCH-001-master-update.md
    └── CR-CLAUDE-001-handoff.md
```

---

## Claude-агент

```bash
claude --print "<промпт с текстом письма>"
```

```json
{
  "type": "REQUEST|DRAFT|NOISE",
  "emailId": "...",
  "taskLine": "- [ ] [P1] ...",
  "taskTitle": "...",
  "priority": "LOW|NORMAL|HIGH|CRITICAL",
  "sender": "...",
  "draftPath": "...",
  "note": "..."
}
```

---

## MCP серверы

| Сервер | Что даёт агенту |
|--------|----------------|
| `jira` | Тикеты, баги, спринты |
| `confluence-team` | Вики команды |
| `confluence-corp` | Корпоративные стандарты |
| `kubernetes` | Состояние кластера |
| `bitbucket` | Репозитории, PR |
| `jenkins` | CI/CD пайплайны |
| `postgres` | Прямой доступ к JavaMemoryService БД |

---

## Окружения

| Профиль | Почта | memory-service | rag-service |
|---------|-------|----------------|-------------|
| `local` | Maildev Docker | опционально | опционально |
| `dev` | IMAP стенд | localhost:8082 | localhost:8081 |
| `prod` | Exchange EWS | localhost:8082 | localhost:8081 |

```bash
docker compose up -d
SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar
SPRING_PROFILES_ACTIVE=local java -jar JavaRagService/target/rag-service.jar
SPRING_PROFILES_ACTIVE=local java -jar JavaMailAgent/target/mail-agent.jar
```

---

## Связи между сервисами

```
JavaMailAgent  ──POST /api/tasks/pending──→  JavaMemoryService
JavaMailAgent  ──запускает──→  claude --print
JavaMemoryService ──GET /api/calendar/today──→  JavaMailAgent  (NEW — идея 8)
claude --print ──читает──→  JavaRagService (через MCP или HTTP /api/search)
```

---

## Что ещё планируется (Future)

- **SMTP отправка** — тип `SEND`
- **Capture Bot** (CR-MEM-001) — модуль приёма заметок в java-memory-service
- **Task File Storage** (CR-MEM-002) — файлы задач workspace/tasks/TASK-{id}.md
- **Calendar Endpoint** (CR-MAIL-001) — GET /api/calendar/today из EWS
- **Weekly Routine Manager** (идея 8) — UI routines + briefing по расписанию
- **End of Day Summary** (идея 9) — git diff + резюме + EOD коммит
- **LeaderOS Daily Cycle** — суточный цикл фреймворка (отдельный RFC)
- **Grafana** — capacity из Jira + PostgreSQL (идея 3)

---

## Maven координаты

| Сервис | groupId | artifactId |
|--------|---------|------------|
| JavaMailAgent | `ru.andreyz.mailagent` | `mail-agent` |
| JavaMemoryService | `ru.andreyz.memoryservice` | `memory-service` |
| JavaRagService | `ru.andreyz.ragservice` | `rag-service` |

---

## RFC документы

| Сервис | RFC | Статус |
|--------|-----|--------|
| JavaMailAgent | `JavaMailAgent/RFC/RFC-JavaMailAgent.md` | ✅ Ready |
| JavaMemoryService | `JavaMemoryService/RFC/RFC-memory-service.md` | ✅ Ready |
| JavaRagService | `JavaRagService/RFC/RFC-rag-service.md` | ✅ Ready |

---

## CR (Change Request) Workflow

Любое изменение в сервисе оформляется через CR — это сохраняет историю решений.

### Префиксы

| Префикс | Сервис / файл |
|---------|--------------|
| `MEM` | JavaMemoryService |
| `RAG` | JavaRagService |
| `MAIL` | JavaMailAgent |
| `CLAUDE` | CLAUDE.md |
| `ARCH` | ARCHITECTURE.md |
| `TEST` | test-runner / E2E сценарии |
| `BUGFIX` | исправление бага по результатам тестов (суффикс к PREFIX) |

### Структура папок

```
Leader-Role-Framework/
└── cr/
    └── CR-ARCH-001-master-update.md

JavaMemoryService/
└── cr/
    ├── CR-001-capture-bot.md
    └── CR-002-claude-capture.md

JavaRagService/
└── cr/
    └── (пусто)

JavaMailAgent/
└── cr/
    ├── CR-001-mock-agent-and-connection-check.md
    └── CR-002-processed-emails-tracking.md
```

### Процесс

```
1. Новая идея / фича
        ↓
2. Создать CR-{PREFIX}-{NNN}.md в cr/ нужного сервиса
        ↓
3. Агент читает CR → вносит изменения в RFC (главную спеку)
        ↓
4. CR остаётся как история (не удалять)
```

### Шаблон CR файла

```markdown
# CR-{PREFIX}-{NNN}: Название изменения

**Дата:** YYYY-MM-DD
**Статус:** Draft | Review | Approved | Implemented
**Сервис:** MEM | RAG | MAIL | CLAUDE | ARCH
**Зависимости:** ...

## Проблема / Мотивация
## Решение
## Изменения в API
## Изменения в схеме БД
## Зависимости от других сервисов
## Как тестировать
```

---

## E2E Тестирование

### Философия

Тесты написаны в виде Markdown-сценариев (`test_e2e/*.md`).
Каждый сценарий — последовательность `curl`/`bash` шагов с явным `**Expected:**`.
Claude Code читает сценарии и прогоняет их как агент, сохраняя отчёт.
Разработчик получает отчёт, создаёт `BUGFIX_CR` и повторяет цикл.

```
Сценарий (*.md)
    ↓
Claude Code (агент) прогоняет curl-шаги
    ↓
TEST-REPORT-{date}.md
    ↓
BUGFIX_CR → Claude Code → фикс → следующий прогон
```

---

### Структура test-runner

```
Leader-Role-Framework/
├── test-runner/
│   ├── AGENT.md              ← инструкции для Claude Code агента
│   ├── build.sh              ← сборка всех JAR-ов
│   ├── start-services.sh     ← запуск сервисов (логи → logs/)
│   ├── stop-services.sh      ← остановка сервисов
│   ├── healthcheck.sh        ← проверка всей инфраструктуры
│   └── reports/              ← TEST-REPORT-*.md по каждому прогону
│
├── logs/
│   ├── JavaMemoryService.log
│   ├── JavaMailAgent.log
│   ├── JavaRagService.log
│   ├── build-*.log
│   └── *.pid
│
├── JavaMemoryService/
│   └── test_e2e/             ← юнит E2E сценарии MemoryService
│
├── JavaMailAgent/
│   └── test_e2e/             ← юнит E2E сценарии MailAgent
│
├── JavaRagService/
│   └── test_e2e/             ← юнит E2E сценарии RagService
│
└── e2e-integration/          ← сквозные интеграционные сценарии
```

---

### Запуск инфраструктуры

```bash
# 1. Docker инфраструктура
docker compose up -d

# 2. Сборка JAR-ов
./test-runner/build.sh

# 3. Запуск сервисов
./test-runner/start-services.sh --profile local

# 4. Проверка
./test-runner/healthcheck.sh
```

---

### Запуск тестов (вручную через Claude Code)

```bash
# Юнит E2E — один сервис
"Прочитай test-runner/AGENT.md.
 Прогони сценарии JavaMemoryService/test_e2e/*.
 Сохрани отчёт в test-runner/reports/TEST-REPORT-{дата}-memory.md"

# Интеграционные тесты
"source e2e-integration/env.sh
 Прочитай test-runner/AGENT.md.
 Прогони e2e-integration/01_email_to_pending_task.md
 Сохрани отчёт в test-runner/reports/TEST-REPORT-integration-run1.md"
```

---

### Переменные окружения

Каждый набор сценариев имеет `env.sh` с адресами сервисов.
Загружается перед прогоном: `source {dir}/test_e2e/env.sh`

| Переменная | Назначение | Пример |
|------------|-----------|--------|
| `MAILDEV_URL` | Maildev HTTP API | `http://172.80.2.1:18080` |
| `MAILDEV_SMTP` | Maildev SMTP | `172.80.2.1:1025` |
| `MS_URL` | JavaMemoryService | `http://localhost:8082` |
| `MA_URL` | JavaMailAgent | `http://localhost:8080` |
| `OPENSEARCH_URL` | OpenSearch | `http://localhost:9200` |
| `OLLAMA_URL` | Ollama | `http://localhost:11434` |
| `PGPASSWORD` | PostgreSQL пароль | `mailagent_password` |

---

### Сценарии по сервисам

#### JavaMemoryService (`test_e2e/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_health_check.md` | CRITICAL | actuator/health → UP |
| `02_create_task.md` | HIGH | POST /api/tasks → 201, видна в плане |
| `03_read_daily_plan.md` | HIGH | GET /api/tasks?date= + /api/context + /ui/today |
| `04_edit_task.md` | HIGH | PUT + PATCH /status + file description |
| `05_pending_task_flow.md` | HIGH | PENDING → confirm → TODO / reject → DELETED |
| `06_incidents.md` | HIGH | OPEN → INVESTIGATING → RESOLVED |
| `07_risks.md` | MEDIUM | OPEN → MITIGATED + getContext |
| `08_people_and_notes.md` | MEDIUM | карточка + заметки + поиск |
| `09_mcp_tools.md` | HIGH | SSE flow + tools/list + getTasks |
| `10_task_reorder_and_move.md` | MEDIUM | reorder + move to date |
| `11_ui_smoke.md` | MEDIUM | все /ui/* страницы, H2 console, 404 |

#### JavaMailAgent (`test_e2e/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_health_check.md` | CRITICAL | actuator/health, Maildev, UI /ui/status |
| `02_maildev_send_receive.md` | HIGH | SMTP → Maildev API, read-статус |
| `03_poll_cycle_noise.md` | HIGH | BUILD/passed → NOISE → markAsRead |
| `04_poll_cycle_request.md` | HIGH | дедлайн → REQUEST → today.md + unread |
| `05_deduplication.md` | HIGH | письмо обрабатывается ровно один раз |
| `06_multiple_emails.md` | MEDIUM | 3 типа за один poll + корректные read-статусы |
| `07_integration_memory_service.md` | HIGH | REQUEST → POST /api/tasks/pending → PENDING |

#### JavaRagService (`test_e2e/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_health_check.md` | CRITICAL | сервис, OpenSearch, Ollama, PostgreSQL |
| `02_index_single_document.md` | HIGH | rag_index + idempotency + OpenSearch чанки |
| `03_semantic_search.md` | HIGH | семантический поиск на кириллице |
| `04_scheduler_auto_index.md` | HIGH | file watcher → авто-индексация ≤90 сек |
| `05_index_directory.md` | MEDIUM | rag_index_directory + паттерн *.md |
| `06_reindex_on_change.md` | MEDIUM | старые чанки удаляются, новые приходят |

#### Интеграционные (`e2e-integration/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_email_to_pending_task.md` | CRITICAL | письмо → REQUEST → PENDING в MemoryService |
| `02_pending_confirm_reject.md` | CRITICAL | PENDING → confirm → TODO / reject → DELETED |
| `03_noise_no_task_created.md` | HIGH | NOISE → письмо прочитано, задача НЕ создана |
| `04_draft_no_task_created.md` | HIGH | DRAFT → черновик в drafts/, задача НЕ создана |
| `05_mixed_batch_three_types.md` | HIGH | 3 письма → правильные типы и read-статусы |
| `06_full_daily_cycle.md` | HIGH | письмо → PENDING → TODO → IN_PROGRESS → DONE |

---

### Формат сценария

```markdown
# Scenario: <название>

**service:** <JavaMemoryService | JavaMailAgent | JavaRagService>
**port:** <порт>
**priority:** <CRITICAL | HIGH | MEDIUM | LOW>
**depends_on:** <postgres | maildev | opensearch | ollama>

## Steps

### Step N — <описание>
```bash
<curl или bash команда>
```
**Expected:** <HTTP код, строка в теле, или значение>
**Extract:** `переменная` из ответа → `$VAR`

## Cleanup
```bash
<удаление тестовых данных>
```
```

---

### Формат отчёта

Отчёт сохраняется в `test-runner/reports/TEST-REPORT-{date}-{service}.md`.

```markdown
# TEST-REPORT-2026-06-11-memory

| Сценарий | PASS | FAIL | Примечание |
|----------|------|------|------------|
| 01_health_check | ✅ | — | |

## Обнаруженные дефекты
### CR-MEM-BUGFIX-001 — описание
```

---

### BUGFIX_CR workflow

```
1. TEST-REPORT содержит FAIL
        ↓
2. Создать {Service}/cr/CR-{PREFIX}-BUGFIX-{NNN}-{name}.md
        ↓
3. Передать CR в Claude Code для исправления
        ↓
4. Пересобрать: mvn package -q -DskipTests
        ↓
5. Повторить прогон → новый отчёт
        ↓
6. Повторять пока все сценарии PASS
```

---

### Правила изоляции тестов

- Уникальные маркеры: `E2E:`, `e2e-test-`, `e2e-pending-`
- Cleanup в каждом сценарии
- Count-проверки через `.select(.id == $ID)`, не `length` всего списка
- Тесты устойчивы к повторным прогонам (idempotent)

---

## Правила коммитов

**Формат:** `{PREFIX}_{тип}_{номер} {краткое описание}`

| Тип | Когда |
|-----|-------|
| `cr` | изменение по Change Request |
| `bugfix` | исправление бага |
| `manual` | ручное изменение без CR |
| `eod` | автоматический коммит конца дня |

**Примеры:**
```
MEM_cr_001 добавлен capture bot модуль
MEM_cr_002 task file storage реализован
RAG_cr_001 подключён multilingual-e5-large
MAIL_bugfix_042 исправлен парсинг EWS дат
ARCH_manual обновлена схема связей сервисов
INFRA_manual добавлен opensearch в docker-compose
MEM_eod_2026-06-09 резюме дня
```

---

## Симлинки на этот файл

Каждый сервис имеет симлинк `ARCHITECTURE.md → ../ARCHITECTURE.md`:

```bash
cd JavaMailAgent        && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
cd ../JavaMemoryService && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
cd ../JavaRagService    && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
```