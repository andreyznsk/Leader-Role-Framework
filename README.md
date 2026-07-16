# LeaderOS — AI-powered Tech Lead Framework

> Персональный фреймворк для автоматизации рутины технического лидера.
> Обработка почты, ведение плана дня, Intake Gateway, база знаний, Jira, мониторинг команды.

---

## Что это такое

LeaderOS — система из трёх Java-сервисов + общей библиотеки, оркестрируемых Claude AI агентом.
Фреймворк берёт на себя операционную рутину: читает почту, классифицирует входящие сигналы,
ведёт базу знаний и задачи, и позволяет сосредоточиться на архитектурных и командных решениях.

Подробная мастер-спека архитектуры: [ARCHITECTURE.md](ARCHITECTURE.md).
Реестр всех Change Request'ов: [docs/cr/REGISTRY.md](docs/cr/REGISTRY.md).

---

## Архитектура

```
┌─────────────────────────────────────────────────────────────────┐
│                          LeaderOS                                │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    common :library                        │   │
│  │  AgentClient: claude | mock | ollama | gigachat           │   │
│  │  JiraClient: shared Jira REST client (JavaMemoryService)  │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌─────────────────┐  HTTP   ┌──────────────────────────────┐   │
│  │  JavaMailAgent  │────────→│      JavaMemoryService       │   │
│  │  :8080          │ /api/   │      :8082                   │   │
│  │  schema:        │ intake  │  schema: memory               │   │
│  │  mailagent      │         │  Intake Gateway · Tasks ·     │   │
│  └────────┬────────┘         │  Incidents · Risks · People · │   │
│           │ claude --print   │  Agent Workspace · Jira flow  │   │
│           ↓                  └──────────┬───────────────────┘   │
│  ┌─────────────────┐                    │ MCP (14+ tools)       │
│  │  Claude Agent   │←───────────────────┘                       │
│  │  (Claude Code)  │←──MCP──→ ┌──────────────────────────────┐  │
│  └─────────────────┘          │      JavaRagService          │  │
│                                │      :8081                   │  │
│                                └──────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

Инфраструктура (Docker):
  PostgreSQL :5432  ←  три изолированных схемы (mailagent, memory, rag)
  OpenSearch :9200  ←  векторная база знаний
  OpenSearch Dashboards :5601
  Maildev :18080    ←  только profile local
  Jira stub :19997  ←  только profile jira-stub (CR-MEM-035)

Нативно (не в Docker):
  Ollama :11434     ←  эмбеддинги mxbai-embed-large
  Java-сервисы      ←  fat-jar, запуск локально
```

### Схема модулей

```
┌──────────────────────────────────────────────────────────────┐
│  JavaMailAgent (:8080)                                        │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────────────┐  │
│  │MailClient  │  │AgentClient  │  │POST /api/intake      │  │
│  │(Maildev/   │→ │(common)     │→ │→ JavaMemoryService    │  │
│  │ EWS/IMAP*) │  └─────────────┘  └──────────────────────┘  │
│  └────────────┘  ┌─────────────┐                             │
│                  │Retry/Checkpoint state-machine по          │
│                  │processed_emails (status/failed_route)     │
│                  └─────────────┘                             │
└──────────────────────────────────────────────────────────────┘
* IMAP: планируется, ещё не реализован

┌──────────────────────────────────────────────────────────────┐
│  JavaMemoryService (:8082)                                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────────────┐ │
│  │REST API  │ │Thymeleaf │ │MCP Server│ │Intake Gateway    │ │
│  │/api/*    │ │UI /ui/*  │ │14+ tools │ │/ui/intake        │ │
│  └──────────┘ └──────────┘ └──────────┘ └─────────────────┘ │
│  ┌──────────────┐ ┌────────────────┐ ┌──────────────────┐   │
│  │CaptureBot    │ │Agent Workspace │ │Jira issue flow   │   │
│  │Scheduler     │ │Chat / Console  │ │(CR-MEM-035)      │   │
│  └──────────────┘ └────────────────┘ └──────────────────┘   │
│  PostgreSQL schema: memory                                    │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  JavaRagService (:8081)                                       │
│  ┌──────────────┐  ┌────────────┐  ┌──────────────────────┐ │
│  │FileIndexer   │  │RagSearch   │  │MCP Tools             │ │
│  │rag-inbox/    │→ │Ollama→     │  │rag_index/search/     │ │
│  │watcher       │  │OpenSearch  │  │index_directory/status│ │
│  └──────────────┘  └────────────┘  └──────────────────────┘ │
│  PostgreSQL schema: rag (indexed_documents)                   │
└──────────────────────────────────────────────────────────────┘
```

---

## Доступные функции

### common (shared library)
- `AgentClient` — единый контракт вызова LLM для всех сервисов, провайдер выбирается через `agent.provider`:
  `claude` (`claude --print` subprocess), `mock` (детерминированный, для local/e2e), `ollama` (Spring AI), `gigachat` (Spring AI, корпоративный стенд)
- `JiraClient` / `AtlassianJiraClient` — общий REST-клиент для Jira-интеграции (используется JavaMemoryService)

### JavaMailAgent
- Чтение входящей почты (Maildev / Exchange EWS; IMAP — запланировано)
- Классификация писем через Claude: `REQUEST / DRAFT / NOISE / CAPTURE / RAG / NOTE`
- Каждый результат классификации создаёт **Intake item** (`POST /api/intake`) в JavaMemoryService вместо прямой записи в operational-сущности
- Retry/checkpoint state-machine на `processed_emails` (status `NEW/PROCESSING/ERROR/PROCESSED`, checkpoint по `failed_route`) — устойчивость к падениям на любом шаге pipeline
- Дедупликация входящих писем
- Control Plane API: settings, status, audit, endpoint-detection, test-connection, plugin on/off без рестарта процесса
- EWS: NTLM/BASIC авторизация, рекурсивное сканирование папок с исключениями (`mail.folders.exclude`)
- Web UI: http://localhost:8080/ui/status

### JavaMemoryService
- **Intake Gateway** (`/ui/intake`) — единая точка входа для всех agent-originated и mail-derived сигналов: review, bulk apply/reject, маршрутизация в `TASK/NOTE/RAG/RISK/INCIDENT/PERSON/NOISE`
- Ежедневный план задач (`/ui/today`) с приоритетами, статусами, вкладками ToDo/Done
- Статусы задачи: `PENDING → TODO → RESEARCH → IN_PROGRESS → DELEGATED → DONE / ARCHIVED`
- Делегирование задач исполнителю + пользовательские labels, timeline назначений
- Вложения задач (файл или внешняя ссылка) и связи между задачами (`RELATES_TO/BLOCKS/DUPLICATES/PARENT_OF` с зеркальным обратным направлением)
- **Jira issue flow** (CR-MEM-035) — создание Jira issue напрямую из задачи Today (`/api/tasks/{id}/jira/*`), идемпотентно, с allowlist проектов
- Управление инцидентами (P1/P2/P3) и рисками, карточки людей и хронологические заметки
- Capture Bot: `POST /api/capture` сохраняет сырую заметку без интерпретации; scheduler по cron классифицирует пакетно в Intake items
- **Global Search** (`/ui/search`) — PostgreSQL FTS по operational-слоям + RAG search по знаниям, с переходом сразу в edit-flow найденной сущности
- **Agent Workspace** (`/ui/agent-workspace`) — Chat и Console режимы работы с агентом (WebSocket-консоль, аудит запусков)
- Usage Statistics (`/ui/stats`) — вопросы к агенту, RAG search, задачи, captures, оценка сэкономленного времени
- Control Plane (`/ui/settings`) — единая точка настройки всех plugin-сервисов (mail, rag) через proxy, включая live-редактирование промптов без рестарта
- MCP Server: `getContext`, `getTasks`, `getTaskDescription`, `searchPeople`, `getTaskLinks`, `proposeTask`, `proposeTaskLink`, `proposeRisk/RiskUpdate`, `proposeIncident/IncidentUpdate`, `proposePersonNote` — агент пишет только через `propose*` (Intake), не напрямую
- Web UI: `/ui/today`, `/ui/notes`, `/ui/captures`, `/ui/search`, `/ui/knowledge`, `/ui/intake`, `/ui/stats`, `/ui/settings`, `/ui/agent-workspace`, `/ui/incidents`, `/ui/risks`, `/ui/people`

### JavaRagService
- Семантический поиск по Markdown-документации (ADR, SERVICE_CARD, PROCESS, GLOSSARY)
- Автоиндексация папки `rag-inbox/` каждые ~60 секунд, идемпотентная переиндексация по SHA-256
- Валидация структуры документов — невалидные не попадают в OpenSearch (статус `invalid`)
- MCP tools: `rag_index`, `rag_search`, `rag_index_directory`, `rag_status`
- REST API для прямого доступа (используется также как proxy через `/api/knowledge/**` в JavaMemoryService)
- Control Plane API (settings, status, audit) как и у остальных plugin-сервисов

### MCP-серверы, доступные Claude-агенту (`.mcp.json`)
| Сервер | Назначение | Требует настройки |
|--------|-----------|---------------------|
| `memory` | JavaMemoryService — задачи, планы, инциденты, риски, люди | нет (localhost) |
| `rag` | JavaRagService — семантическая база знаний | нет (localhost) |
| `postgres` | Прямой read-доступ к схеме `memory` | `POSTGRES_MEMORY_PASSWORD` |
| `jira` | Тикеты, баги, спринты | `JIRA_URL`, `JIRA_EMAIL`, `JIRA_TOKEN` |
| `confluence-team` / `confluence-corp` | Вики команды / корпоративный Confluence | `CONFLUENCE_*_URL`, `CONFLUENCE_EMAIL`, `CONFLUENCE_*_TOKEN` |
| `kubernetes` | Состояние кластера | `KUBECONFIG` (по умолчанию `~/.kube/config`) |
| `bitbucket` | Репозитории, PR (self-hosted) | `BITBUCKET_URL`, `BITBUCKET_TOKEN` |
| `jenkins` | CI/CD пайплайны | `JENKINS_URL`, `JENKINS_USER`, `JENKINS_TOKEN` |
| `filesystem` | Локальный доступ к `workspace/`, `plans/`, `capture-inbox/`, `rag-inbox/` | нет |
| `playwright` | Управление браузером для UI-проверок | нет |

Все внешние интеграции (`jira`, `confluence-*`, `kubernetes`, `bitbucket`, `jenkins`) опциональны —
без переменных окружения соответствующий MCP-сервер просто не будет доступен агенту, остальная система работает без них.

---

## Пререквизиты

Java 21+, Maven 3.9+, Docker 24+, Claude Code (latest), Ollama (latest)

Проверить: `java -version && mvn -version && docker --version && claude --version && ollama --version`

---

## Минимальные настройки для старта (local профиль)

Чтобы поднять весь стек локально **без единого внешнего токена**, достаточно:

1. **Инфраструктура:**
   ```bash
   docker compose up -d                     # postgres + opensearch + dashboards
   docker compose --profile local up -d     # + maildev (SMTP-заглушка)
   ```
2. **Эмбеддинги:** `ollama pull mxbai-embed-large` и запустить `ollama serve` (нативно, не в Docker)
3. **Провайдер LLM:** для сервисов уже закоммичен `agent.provider=mock` в `application-local.yml` — этого
   достаточно, чтобы поднять систему и погонять E2E без установленного/авторизованного Claude CLI.
   Чтобы использовать реального Claude-агента — поменять на `agent.provider=claude` (требует `claude --version` рабочий и авторизованный).
4. **БД:** креды `*_user/*_password` для схем `mailagent/memory/rag` уже зашиты в `application-local.yml`
   каждого сервиса и в `infra/postgres/init.sql` — ничего создавать вручную не нужно.
5. **Почта:** `mail.protocol=maildev` в `JavaMailAgent/application-local.properties.example` — скопировать
   в `application-local.properties` рядом, ничего дополнительно настраивать не требуется.
6. **Сборка и запуск:**
   ```bash
   ./test-runner/build.sh
   ./test-runner/start-services.sh --profile local
   ```

Всё остальное — `.mcp.json` (jira/confluence/kubernetes/bitbucket/jenkins), Jira issue flow,
EWS-профиль — **опционально** и требуется только для соответствующих функций (см. таблицу MCP выше и разделы ниже).

---

## Установка

1. `git clone https://github.com/andreyznsk/Leader-Role-Framework.git`
2. `ollama pull mxbai-embed-large`
3. Скопировать `application-local.properties.example` → `application-local.properties` для `JavaMailAgent`
   (у `JavaMemoryService`/`JavaRagService` `application-local.yml` уже закоммичен с рабочими дефолтами)
4. Создать симлинки `ARCHITECTURE.md` в каждом сервисе (уже сделано в репозитории — проверить `ls -la */ARCHITECTURE.md`)
5. `docker compose up -d`
6. `./test-runner/build.sh`

---

## Запуск

```bash
./test-runner/start-services.sh --profile local
```

Или вручную из корня `Leader-Role-Framework/`:
```bash
SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar
SPRING_PROFILES_ACTIVE=local java -jar JavaRagService/target/rag-service.jar
SPRING_PROFILES_ACTIVE=local java -jar JavaMailAgent/target/mail-agent.jar
```

---

## Health check

```bash
./test-runner/healthcheck.sh
```

Web UI:
- http://localhost:8082/ui/today    — план дня
- http://localhost:8082/ui/intake   — Intake Gateway
- http://localhost:8082/ui/search   — Global Search
- http://localhost:8082/ui/agent-workspace — Agent Workspace (Chat/Console)
- http://localhost:8080/ui/status   — почтовый агент
- http://localhost:5601             — OpenSearch Dashboards

### Jira stub for local E2E

Для `CR-MEM-035` и локального тестирования Jira flow можно поднять лёгкий HTTP stub:

```bash
docker compose --profile jira-stub up -d jira-stub
```

Использовать в `JavaMemoryService`:

```bash
JIRA_ENABLED=true
JIRA_BASE_URL=http://127.0.0.1:19997
JIRA_TOKEN=test-token
JIRA_DEFAULT_PROJECT=ENG
JIRA_ALLOWED_PROJECTS=ENG,OPS
```

Stub реализует минимальные endpoints MVP-flow:
- `GET /rest/api/2/myself`
- `GET /rest/api/2/project/ENG`
- `GET /rest/api/2/project/OPS`
- `GET /rest/api/2/user/assignable/search`
- `POST /rest/api/2/issue`

---

## E2E тесты

Smoke: `claude --print "Прочитай test-runner/AGENT.md. Прогони CRITICAL сценарии всех сервисов."`
Full:  `claude --print "Прочитай test-runner/AGENT.md. Прогони JavaRagService/test_e2e/*."`

Интеграционные сценарии между сервисами — `e2e-integration/`.

JavaRagService: 44 PASS / 0 FAIL (2026-06-12)

---

## Профили

| Профиль | Почта |
|---------|-------|
| `local` | Maildev (Docker), разработка |
| `dev`   | IMAP, тестовый сервер (планируется) |
| `ews`   | Exchange EWS, отдельный профиль подключения |
| `prod`  | Exchange EWS, рабочий ПК |

### EWS profile

For a dedicated Exchange setup use:

```bash
SPRING_PROFILES_ACTIVE=ews java -jar JavaMailAgent/target/mail-agent.jar
```

Config file:
- `JavaMailAgent/src/main/resources/application-ews.yml`
- example overrides: `JavaMailAgent/application-ews.properties.example`

Required settings for EWS:
- `mail.protocol=ews`
- `mail.username`
- `mail.password`
- `ews.url`

Recommended settings:
- `ews.auth-type=NTLM`
- `ews.autodiscover=false`
- `ews.timeout-seconds=30`
- `mail.test-connection.timeout-seconds=15`
- `mail.test-connection.max-folders-to-scan=500`

Optional settings:
- `ews.domain` for `DOMAIN\\user` auth
- `ews.version` if your Exchange requires a specific EWS version hint
- `mail.folders.exclude` to skip CI/CD and service folders

### EWS diagnostics

Mail Agent settings in `http://localhost:8082/ui/settings` now expose:
- `Authentication Type` (`BASIC`, `NTLM`, `OAUTH2`)
- `Detect Endpoint`
- `Test Connection`

For `Protocol = EWS`, UI defaults `Authentication Type` to `NTLM`.

Endpoint detection:

```http
POST /api/settings/control/plugins/mail/detect-endpoint
Content-Type: application/json
```

Example payload:

```json
{
  "protocol": "ews",
  "ewsUrl": "https://outlook.domain.ru/EWS/Exchange.asmx"
}
```

This checks HTTPS reachability and whether the URL looks like a real EWS/WCF service. It does not require credentials and recommends `NTLM` for EWS.

Browser-facing endpoint:

```http
POST /api/settings/control/plugins/mail/test-connection
Content-Type: application/json
```

Example payload:

```json
{
  "protocol": "ews",
  "ewsUrl": "https://outlook.domain.ru/EWS/Exchange.asmx",
  "username": "user@domain.ru",
  "password": "",
  "authType": "NTLM",
  "folderExclude": ["Inbox/CI/CD"]
}
```

The authenticated test validates connectivity only. It does not start polling, write `processed_emails`, or flip read/unread state. Empty `password` means reuse the already stored secret. `OAUTH2` is visible in UI as planned and currently returns a not-supported result from backend.

---

## Структура проекта

```
Leader-Role-Framework/
│
├── CLAUDE.md / AGENT.md         ← Главный промпт агента (симлинки)
├── ARCHITECTURE.md              ← Living-документ мастер-спеки архитектуры
├── .mcp.json                    ← MCP серверы (НАСТРОЙ ТОКЕНЫ для внешних)
│
├── .claude/
│   ├── settings.json            ← Permissions и настройки
│   ├── agents/                  ← Суб-агенты (arch-analyst, k8s-arch-analyst,
│   │                               risk-scanner, doc-writer, signal-filter, e2e-test-runner)
│   └── commands/                ← Slash-команды (/standup, /week-review)
│
├── skills/                      ← Скиллы (промпты под задачи)
│   ├── arch-mapper.md
│   ├── people-mapper.md
│   ├── release-prep.md
│   ├── incident-playbook.md
│   ├── daily-journal.md
│   └── risk-scan.md
│
├── common/                      ← AgentClient + Jira client, общая библиотека
├── JavaMailAgent/                ← :8080, почта → Intake
├── JavaMemoryService/            ← :8082, operational memory + Intake Gateway + MCP
├── JavaRagService/                ← :8081, RAG knowledge base
│
├── docs/cr/                     ← Change Requests + REGISTRY.md
├── e2e-integration/              ← кросс-сервисные E2E сценарии
├── capture-inbox/                ← сырые заметки Capture Bot
├── rag-inbox/                    ← документы на индексацию
│
└── workspace/                    ← «второй мозг» — артефакты Tech Lead-а
    ├── 00_people/                ← Stakeholder Map
    ├── 01_services/              ← Карта архитектуры (C4, ADR, service cards)
    ├── 02_processes/             ← Release flow, процессы команды
    ├── 03_incidents/             ← Постмортемы
    ├── 04_releases/              ← Release Notes, чеклисты
    ├── 05_questions/             ← Открытые вопросы
    ├── 06_decisions/             ← ADR
    ├── 07_risks/                 ← Operational risks
    ├── 08_daily_journal/         ← Дневник
    ├── attachments/              ← файловые вложения задач (CR-MEM-030)
    └── tasks/                    ← export/backup markdown задач (не source of truth)
```
