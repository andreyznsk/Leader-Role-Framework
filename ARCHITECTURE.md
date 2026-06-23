# LeaderOS — Architecture - Мастер-Спека

**Последнее обновление:** 2026-06-23
**Статус:** Living document — обновлять при любом изменении контрактов между сервисами
**git:** https://github.com/andreyznsk/Leader-Role-Framework.git
---

## Обзор системы

AI-powered фреймворк техлида. Автоматизирует рутину: обработку почты,
ведение плана дня, работу с документацией, мониторинг команды.

```
┌─────────────────────────────────────────────────────────────────┐
│                          LeaderOS                                │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    common :library                        │   │
│  │  AgentClient: claude | mock | ollama | gigachat           │   │
│  └──────────────────────────────────────────────────────────┘   │
│        ↑ использует         ↑ использует         ↑ depends       │
│  ┌─────────────────┐        ┌──────────────────────────────┐    │
│  │  JavaMailAgent  │──────→ │      JavaMemoryService       │    │
│  │  :8080          │  HTTP  │      :8082                   │    │
│  │  schema:        │        │  schema: memory              │    │
│  │  mailagent      │        │  PostgreSQL (Docker :5432)   │    │
│  └─────────────────┘        └──────────────────────────────┘    │
│                                                                  │
│  ┌─────────────────┐        ┌──────────────────────────────┐    │
│  │  Maildev        │        │      JavaRagService          │    │
│  │  Docker :18080  │        │      :8081                   │    │
│  │  SMTP   :1025   │        │  OpenSearch (Docker :9200)   │    │
│  │  local only     │        │  Ollama (local :11434)       │    │
│  └─────────────────┘        └──────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
```

---

## Сервисы

### common
**Тип:** plain JAR, не Spring Boot app, не fat-jar
**RFC:** `common/RFC/RFC-common.md`
**Статус:** Implemented

**Роль:** Общая инфраструктура LeaderOS. Содержит единый внешний контракт
`AgentClient` для вызова LLM из сервисов.

**Интерфейс:**

| Класс | Описание |
|-------|----------|
| `AgentClient` | `String complete(String prompt)` |
| `AgentException` | RuntimeException для ошибок LLM |
| `ClaudeProcessAgentClient` | `claude --print` subprocess |
| `MockAgentClient` | deterministic mock, включая keyword-based mail/capture классификацию |
| `OllamaAgentClient` | Spring AI → Ollama |
| `GigaChatAgentClient` | Spring AI → GigaChat |
| `AgentClientConfig` | auto-configuration, выбор по `agent.provider` |

**Переключение провайдера:**
```yaml
agent:
  provider: claude   # claude | mock | ollama | gigachat
```

### JavaMailAgent
**Порт:** 8080
**Тип:** Spring Boot 3, fat-jar, запускается из корня проекта
**RFC:** `JavaMailAgent/RFC/RFC-JavaMailAgent.md`
**Статус:** In Progress

**Роль:** Читает входящую почту, вызывает LLM через `AgentClient` на каждое письмо,
выполняет детерминированное действие по результату классификации.

**База данных:** PostgreSQL, схема `mailagent`, владелец `mailagent_user`
**Миграции:** Flyway, `classpath:db/migration`, только схема `mailagent`

**Протоколы подключения к почте:**

| Профиль | Протокол | Клиент | Статус |
|---------|----------|--------|--------|
| local | Maildev HTTP API | `MaildevClient` | ✅ реализован |
| dev | IMAP | `ImapMailClient` | 🔜 planned |
| prod | EWS (Exchange on-premise) | `EwsMailClient` | ✅ реализован |

**Prod Exchange scan:** `EwsMailClient` рекурсивно сканирует `Inbox` и все подпапки.
Исключения задаются в `mail.folders.exclude` по имени папки или полному пути
от Inbox, например `Inbox/CI/CD`.

**Классификация писем (enum AgentResponseType):**

| Тип | Действие |
|-----|----------|
| `REQUEST` | Добавить в `plans/today.md` + POST `/api/tasks/pending` в JavaMemoryService |
| `DRAFT` | Сохранить черновик в `drafts/` |
| `NOISE` | Пометить прочитанным на сервере, переместить в `processed/` |
| `CAPTURE` | POST `/api/capture` в JavaMemoryService, переместить файл в `processed/` |

**Трекинг обработанных писем:** таблица `mailagent.processed_emails`
- `REQUEST` и `DRAFT` — письмо остаётся непрочитанным на сервере
- `NOISE` — помечается прочитанным на сервере

**Исходящие вызовы:**
- `POST http://localhost:8082/api/tasks/pending` — создать PENDING задачу

**UI:** `http://localhost:8080/ui/status` — статус агента, счётчики, последние логи

**Plugin Control API (CR-MAIL-004, ✅ Implemented):**

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/control/settings` | Настройки плагина как `ControlSettingsDescriptor` (key → value/type/label/editable/secret/options) |
| `PUT` | `/api/control/settings` | Применить изменения. Body: `{"settings":{...}}`. Ответ: `{status,keys,configVersion,message}` |
| `GET` | `/api/control/status` | Статус: `{pluginCode,pluginName,enabled,schedulerEnabled,configVersion,...}` |
| `GET` | `/api/control/audit` | История изменений настроек |
| `POST` | `/api/control/plugin-state` | `{enabled:true/false}` — включить/выключить polling внутри JVM (без остановки процесса) |
| `POST` | `/api/control/test-connection` | Тест подключения к почтовому серверу. **200** `{success:true}` / **500** `{success:false}` |

**Важно:** `enabled=false` останавливает polling/классификацию внутри JVM. Процесс mail-agent продолжает работать.

**RuntimeMailClient:** выбирает реализацию на каждый вызов по `runtimeConfigService.snapshot().protocol()`:
- `maildev` → `MaildevClient` (статические свойства из конфига)
- `ews` → `EwsMailClient` (runtime `serverUrl` из базы)
- `imap` → `MailException` (not implemented)

**Новые таблицы БД:**

| Таблица | Описание |
|---------|----------|
| `mailagent.control_settings_audit` | История изменений настроек (action, keys, status, message, createdAt) |

---

### JavaMemoryService
**Порт:** 8082
**Тип:** Spring Boot 3, fat-jar
**RFC:** `JavaMemoryService/RFC/RFC-memory-service.md`
**Статус:** In Progress

**Роль:** Операционная память техлида и UI/gateway для базы знаний.
Хранит быстро меняющиеся данные — задачи, планы, инциденты, риски, людей,
заметки и raw captures. RAG-документами не владеет, но отдаёт browser-facing
UI/API для работы с JavaRagService. Даёт REST, Thymeleaf UI и MCP tools для Claude-агента.

**База данных:** PostgreSQL, схема `memory`, владелец `memory_user`

**Схемы PostgreSQL:**
```
БД: leader_framework
├── schema: mailagent   ← JavaMailAgent        (owner: mailagent_user)
├── schema: memory      ← JavaMemoryService    (owner: memory_user)
└── schema: rag         ← JavaRagService       (owner: rag_user)
```
Каждый сервис управляет только своей схемой через Flyway.
Инициализация: `infra/postgres/init.sql` — схемы, пользователи, права.

**Новые таблицы (CR-MEM-009):**

| Таблица | Описание |
|---------|----------|
| `memory.control_plugins` | Реестр зарегистрированных плагинов (code, name, baseUrl, status, lastSyncAt) |
| `memory.control_plugin_settings_snapshot` | Последний известный snapshot настроек плагина (для offline-рендеринга) |
| `memory.control_plugin_audit` | История изменений настроек через control plane proxy |

**Ключевые endpoint-ы:**

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/context` | Контекст сессии: today/tomorrow, open incidents/risks, recent people notes |
| `POST` | `/api/tasks` | Создать подтверждённую задачу |
| `POST` | `/api/tasks/pending` | Создать задачу со статусом PENDING |
| `GET` | `/api/tasks?date=YYYY-MM-DD` | Задачи на дату, без `DELETED` по умолчанием |
| `PATCH` | `/api/tasks/{id}/status` | Изменить статус задачи |
| `POST` | `/api/capture` | Сохранить raw capture в БД и `capture-inbox/` |
| `POST` | `/api/capture/process-now` | Ручной запуск классификации capture-файлов |
| `POST` | `/api/knowledge/search` | Memory-owned прокси к JavaRagService `/api/search` + usage events |
| `GET/PUT/POST` | `/api/knowledge/documents/**` | Browser-facing proxy управления RAG-документами |
| `GET` | `/api/stats/usage?period=7d` | Usage Statistics: агрегаты по usage events |
| `GET/POST` | `/api/notes` | Лента заметок |
| `GET/POST/PUT/DELETE` | `/api/incidents`, `/api/risks`, `/api/people` | CRUD/soft delete рабочих сущностей |
| `GET` | `/ui/today` | Web UI: план дня |
| `GET` | `/ui/notes` | Web UI: Operational Notes |
| `GET` | `/ui/captures` | Web UI: Capture Inbox |
| `GET` | `/ui/knowledge` | Web UI: Knowledge Gateway для RAG lifecycle |
| `GET` | `/ui/stats` | Web UI: статистика использования и saved time |
| `GET` | `/ui/settings` | Web UI: Control Plane — настройки плагинов (descriptor-driven UI) |

**Control Plane Proxy (CR-MEM-009, CR-MEM-010, ✅ Implemented):**

MemoryService выступает единой точкой управления для всех plugin-сервисов.
`ControlPluginRegistry` регистрирует плагины: `mail → http://localhost:8080`, `rag → http://localhost:8081`.
`ControlPluginService` проксирует запросы к `/api/control/*` каждого плагина.

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/settings/control/plugins` | Список зарегистрированных плагинов |
| `GET` | `/api/settings/control/plugins/{code}/settings` | Получить настройки плагина через proxy (503 если недоступен) |
| `PUT` | `/api/settings/control/plugins/{code}/settings` | Обновить настройки плагина через proxy |
| `GET` | `/api/settings/control/plugins/{code}/audit` | История изменений плагина через proxy |
| `POST` | `/api/settings/plugins/mail/test-connection` | Тест подключения почты. **200** `{success:true}` / **500** `{success:false}` |

**UI Settings:** `/ui/settings` — Thymeleaf-страница `settings.html`. Рендерит форму настроек на сервере через `ControlSettingsDescriptor`. Данные полностью server-side (Thymeleaf), без client-side API fetch при загрузке. Форма сохраняется через browser fetch → `PUT /api/settings/control/plugins/{code}/settings`.

**Статусы задачи:** `PENDING → TODO → IN_PROGRESS → DONE` / `DELETED`

**Capture Bot:** `POST /api/capture` сохраняет заметку без интерпретации.
`CaptureScheduler` по `capture.scheduler.cron` пакетно читает `capture-inbox/YYYY-MM-DD/*.md`,
добавляет контекст дня, вызывает `AgentClient` из `common` и маршрутизирует результат:
`TASK → tasks/pending`, `RISK → risks`, `NOTE → notes`, `QUESTION → questions`,
`PERSON_NOTE → person_notes`, `KNOWLEDGE → JavaRagService/rag-inbox/captures`,
`JOURNAL → workspace/08_daily_journal`.
После успешной обработки файл переносится в `capture-inbox/processed/YYYY-MM-DD/`.

**Usage Statistics:** Memory Service владеет таблицей `memory.usage_events` и пишет события
из task, pending task, capture, capture processing и knowledge search flow. Агрегаты доступны через
`GET /api/stats/usage?period=today|7d|30d|all`, UI — `/ui/stats`. Saved time считается
по MVP-формуле из CR-MEM-008. Knowledge search и knowledge document management проходят через
REST proxy (`/api/knowledge/**`) и не получают прямого JDBC-доступа к схеме `rag`.

**MCP tools:** `getContext`, `getTasks`, `createTask`, `markTaskDone`, `moveTask`,
`updateTaskStatus`, `getTaskDescription`, `setTaskDescription`, `createIncident`,
`resolveIncident`, `addRisk`, `updateRisk`, `addPeopleNote`, `searchPeople`.

**Входящие вызовы от:** JavaMailAgent, Claude-агент, CLI/user scripts

---

### JavaRagService
**Порт:** 8081
**Тип:** Spring Boot 3, fat-jar
**RFC:** `JavaRagService/RFC/RFC-rag-service.md` ✅
**Статус:** In Progress

**Роль:** RAG (Retrieval-Augmented Generation) — семантическая база знаний техлида.
Хранит стабильные Markdown-документы: ADR, process, glossary, service-cards.

**Зависимости:**
- OpenSearch (Docker) — векторное хранилище, порт 9200 (`172.80.2.1:9200` в local профиле)
- Ollama (локально, не Docker) — эмбеддинги `mxbai-embed-large` (1024 dim), порт 11434
- PostgreSQL (Docker, схема `rag`) — трекинг проиндексированных документов

**База данных:** PostgreSQL, схема `rag`, владелец `rag_user`
**Миграции:** Flyway V1 (таблица `indexed_documents`), V2 (поле `error_message`)

**REST API (`RagRestController`):**

| Метод | Путь | Описание |
|-------|------|----------|
| `POST` | `/api/rag/index` | Индексировать файл → `{chunksAdded, status, filePath}` |
| `POST` | `/api/rag/index-directory` | Batch-индексация → `{indexed, skipped, failed, invalid, message}` |
| `POST` | `/api/search` | Векторный поиск → `[{text, source, score, chunkIndex}]` |
| `GET` | `/api/rag/status` | Список всех документов из PostgreSQL |

**MCP tools (Spring AI):** `rag_index`, `rag_index_directory`, `rag_search`, `rag_status`

**Валидация документов (DocumentValidator):** обязательный YAML frontmatter (`type:`, `updated:`)
и обязательные секции по типу (ADR, SERVICE_CARD, PROCESS, GLOSSARY).
Невалидные документы получают статус `invalid` без попадания в OpenSearch.

**Статусы документа:** `indexed` | `invalid` | `failed` | `outdated`

**Scheduler:** `scheduleWithFixedDelay` каждые ~60 сек, сканирует `rag-inbox/`, идемпотентен по hash

**Входящие вызовы от:** Claude-агент (через MCP или REST API)

**Plugin Control API (CR-RAG-001, ✅ Implemented):**

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/control/settings` | Настройки как `ControlSettingsDescriptor` |
| `PUT` | `/api/control/settings` | Применить изменения. Body: `{"settings":{...}}` |
| `GET` | `/api/control/status` | Статус сервиса: `{pluginCode,pluginName,enabled,schedulerEnabled,...}` |
| `GET` | `/api/control/audit` | История изменений настроек |

**Настройки плагина:** `enabled`, `schedulerEnabled`, `scanIntervalSeconds`, `ragInboxPath`, `embeddingModel`, `topK`, `opensearchUrl`, `validationEnabled`

**`pluginCode = "rag"`, `pluginName = "RAG Service"`**

---

## Инфраструктура (Docker)

### `docker-compose.yml` — корень Leader-Role-Framework (общая инфраструктура)
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
    image: opensearchproject/opensearch:3.5.0
    ports: ["9200:9200"]
    # Доступен как 172.80.2.1:9200 с хоста (bridge IP) в local профиле

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2
    ports: ["5601:5601"]
```

### `JavaMailAgent/docker-compose.yml` — Maildev (только для mail-agent)
```yaml
services:
  maildev:
    image: maildev/maildev:latest
    ports:
      - "18080:1080"  # Web UI + HTTP API
      - "1025:1025"   # SMTP
```

**Ollama** — нативно, не в Docker.
Модель эмбеддингов: `mxbai-embed-large`

---

## Файловая шина

```
Leader-Role-Framework/
├── inbox/              ← JavaMailAgent пишет входящие письма (JSON)
├── processed/          ← письма после обработки
├── drafts/             ← черновики ответов от агента
├── plans/
│   └── today.md        ← план дня
├── capture-inbox/      ← Capture Bot складывает сырые заметки
│   ├── YYYY-MM-DD/
│   │   └── HH-MM-SS.md
│   └── processed/
│       └── YYYY-MM-DD/
│           └── HH-MM-SS.md
├── workspace/
│   ├── tasks/          ← файлы задач по id
│   │   ├── TASK-001.md
│   │   └── TASK-002.md
│   └── 08_daily_journal/
│       └── YYYY-MM-DD.md
├── JavaRagService/
│   └── rag-inbox/
│       └── captures/   ← KNOWLEDGE captures для индексации
└── cr/                 ← CR для ARCHITECTURE.md и CLAUDE.md
    ├── CR-ARCH-001-master-update.md
    └── CR-CLAUDE-001-handoff.md
```

---

## Claude-агент / LLM-агент

Сервисы вызывают LLM через `AgentClient` из модуля `common`.
Провайдер выбирается через `agent.provider` в `application.yml`.

| Провайдер | Реализация | Когда использовать |
|-----------|------------|-------------------|
| `claude` | `claude --print` subprocess | prod/default |
| `mock` | `MockAgentClient` | local/e2e тесты |
| `ollama` | Spring AI → Ollama | local без Claude CLI |
| `gigachat` | Spring AI → GigaChat | корпоративный стенд |

Промпт-билдеры и парсинг ответа остаются в сервисах, потому что формат ответа
является предметной логикой.

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
JavaMailAgent  ──AgentClient──→  common
JavaMemoryService ──capture KNOWLEDGE файл──→  JavaRagService/rag-inbox/captures
JavaMemoryService ──AgentClient──→  common
JavaRagService ──depends on──→  common (future LLM features)
LLM provider ──читает──→  JavaRagService (через MCP или HTTP /api/search)

// Plugin Control Protocol (CR-MAIL-004, CR-RAG-001, CR-MEM-009):
JavaMemoryService ──GET/PUT /api/control/settings──→  JavaMailAgent   (proxy via ControlPluginService)
JavaMemoryService ──GET/PUT /api/control/settings──→  JavaRagService  (proxy via ControlPluginService)
JavaMemoryService ──POST /api/control/test-connection──→  JavaMailAgent
Browser/Agent ──/ui/settings──→  JavaMemoryService ──proxies──→  Plugins
```

**Plugin Control Protocol** — единый контракт для управления plugin-сервисами из MemoryService:
- Каждый plugin-сервис экспонирует `GET/PUT /api/control/settings`, `GET /api/control/status`, `GET /api/control/audit`
- JavaMailAgent дополнительно: `POST /api/control/test-connection`, `POST /api/control/plugin-state`
- JavaMemoryService проксирует все запросы через `ControlPluginService`
- `ControlSettingsDescriptor` — ключ-значение дескриптор настройки: `{value, type, label, description, editable, secret, required, options[]}`
- Типы настроек: `string`, `number`, `boolean`, `select`, `text`, `list`, `secret`

---

## Что ещё планируется (Future)

- **SMTP отправка** — тип `SEND`
- **Capture Bot UI** — расширить ручное управление captures и переклассификацию
- **Calendar Endpoint** (CR-MAIL-001) — GET /api/calendar/today из EWS
- **Weekly Routine Manager** (идея 8) — UI routines + briefing по расписанию
- **End of Day Summary** (идея 9) — git diff + резюме + EOD коммит
- **LeaderOS Daily Cycle** — суточный цикл фреймворка (отдельный RFC)
- **Grafana** — capacity из Jira + PostgreSQL (идея 3)
- **common** — реализован, сервисы мигрированы (CR-COMMON-001)

---

## Maven координаты

| Сервис | groupId | artifactId |
|--------|---------|------------|
| common | `ru.andreyz` | `common` |
| JavaMailAgent | `ru.andreyz.mailagent` | `mail-agent` |
| JavaMemoryService | `ru.andreyz.memoryservice` | `memory-service` |
| JavaRagService | `ru.andreyz.ragservice` | `rag-service` |

---

## RFC документы

| Сервис | RFC | Статус |
|--------|-----|--------|
| common | `common/RFC/RFC-common.md` | Draft |
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
| `COMMON` | common модуль |
| `CLAUDE` | CLAUDE.md |
| `ARCH` | ARCHITECTURE.md |
| `TEST` | test-runner / E2E сценарии |
| `BUGFIX` | исправление бага по результатам тестов (суффикс к PREFIX) |

### Структура папок

```
Leader-Role-Framework/
├── cr/
│   └── CR-ARCH-001-master-update.md
└── docs/
    └── cr/
        ├── CR-MAIL-004-plugin-control-api.md      (Implemented, 2026-06-23)
        ├── CR-MEM-009-plugin-settings-store.md    (Implemented, 2026-06-23)
        ├── CR-MEM-010-universal-plugin-control-ui.md (Implemented, 2026-06-23)
        └── CR-RAG-001-plugin-control-api.md       (Implemented, 2026-06-23)

JavaMemoryService/
└── cr/
    ├── CR-001-capture-bot.md
    └── CR-002-claude-capture.md

JavaRagService/
└── cr/
    ├── CR-RAG-001-postgres-schema.md         (Done)
    ├── CR-RAG-002-document-validation.md     (Approved)
    ├── CR-RAG-BUGFIX-002-rest-api.md         (Approved)
    └── CR-RAG-E2E-001.md                     (Done, 2026-06-12)

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
| `OPENSEARCH_URL` | OpenSearch | `http://172.80.2.1:9200` (local/Docker bridge) |
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

Статус прогона: **2026-06-12 — 44 PASS / 0 FAIL / 0 SKIP**

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_health_check.md` | CRITICAL | сервис, OpenSearch (`/_cluster/health`), Ollama, PostgreSQL |
| `02_index_and_search.md` | HIGH | базовый цикл: создать → POST /api/rag/index → POST /api/search |
| `02_index_single_document.md` | HIGH | полный цикл с проверкой PostgreSQL и OpenSearch чанков |
| `03_semantic_search.md` | HIGH | семантический поиск на кириллице, top_k, релевантность |
| `04_scheduler_auto_index.md` | HIGH | file watcher → авто-индексация ≤90 сек, переиндексация |
| `05_index_directory.md` | MEDIUM | POST /api/rag/index-directory, паттерн *.md, invalid=0 |
| `06_reindex_on_change.md` | MEDIUM | старые чанки удаляются при переиндексации, hash обновляется |

#### Интеграционные (`e2e-integration/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_email_to_pending_task.md` | CRITICAL | письмо → REQUEST → PENDING в MemoryService |
| `02_pending_confirm_reject.md` | CRITICAL | PENDING → confirm → TODO / reject → DELETED |
| `03_noise_no_task_created.md` | HIGH | NOISE → письмо прочитано, задача НЕ создана |
| `04_draft_no_task_created.md` | HIGH | DRAFT → черновик в drafts/, задача НЕ создана |
| `05_mixed_batch_three_types.md` | HIGH | 3 письма → правильные типы и read-статусы |
| `06_full_daily_cycle.md` | HIGH | письмо → PENDING → TODO → IN_PROGRESS → DONE |
| `10_control_plane_settings.md` | HIGH | Plugin Control API: settings fetch/update/testConnection 200/500, audit, UI smoke |

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
