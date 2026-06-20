# LeaderOS — AI-powered Tech Lead Framework

> Персональный фреймворк для автоматизации рутины технического лидера.
> Обработка почты, ведение плана дня, управление знаниями, мониторинг команды.

---

## Что это такое

LeaderOS — система из трёх Java-сервисов, оркестрируемых Claude AI агентом.
Фреймворк берёт на себя операционную рутину: читает почту, классифицирует задачи,
ведёт базу знаний, и позволяет сосредоточиться на архитектурных и командных решениях.

---

## Архитектура

```
┌─────────────────────────────────────────────────────────────────┐
│                          LeaderOS                                │
│                                                                  │
│  ┌─────────────────┐  HTTP   ┌──────────────────────────────┐   │
│  │  JavaMailAgent  │────────→│      JavaMemoryService       │   │
│  │  :8080          │         │      :8082                   │   │
│  └────────┬────────┘         └──────────────────────────────┘   │
│           │ claude --print                                       │
│           ↓                                                      │
│  ┌─────────────────┐         ┌──────────────────────────────┐   │
│  │  Claude Agent   │←──MCP──→│      JavaRagService          │   │
│  │  (Claude Code)  │         │      :8081                   │   │
│  └─────────────────┘         └──────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘

Инфраструктура (Docker):
  PostgreSQL :5432  ←  три изолированных схемы (mailagent, memory, rag)
  OpenSearch :9200  ←  векторная база знаний
  OpenSearch Dashboards :5601
  Maildev :18080    ←  только local профиль

Нативно (не в Docker):
  Ollama :11434     ←  эмбеддинги multilingual-e5-large (Metal на M1)
  Java-сервисы      ←  fat-jar, запуск локально
```

### Схема модулей

```
┌──────────────────────────────────────────────────────────────┐
│  JavaMailAgent (:8080)                                        │
│  ┌────────────┐  ┌─────────────┐  ┌──────────────────────┐  │
│  │MailClient  │  │ClaudeRunner │  │MemoryServiceClient   │  │
│  │(EWS/IMAP/  │→ │(--print)    │→ │POST /api/tasks/      │  │
│  │ Maildev)   │  └─────────────┘  │      pending         │  │
│  └────────────┘  ┌─────────────┐  └──────────────────────┘  │
│                  │ActionExecutor│                             │
│                  │REQUEST/DRAFT │                             │
│                  │/NOISE        │                             │
│                  └─────────────┘                             │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  JavaMemoryService (:8082)                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────┐ │
│  │REST API  │  │Thymeleaf │  │MCP Server│  │CaptureBot   │ │
│  │/api/*    │  │UI /ui/*  │  │14 tools  │  │Scheduler    │ │
│  └──────────┘  └──────────┘  └──────────┘  └─────────────┘ │
│  PostgreSQL schema: memory                                    │
│  workspace/tasks/TASK-{id}.md                                 │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  JavaRagService (:8081)                                       │
│  ┌──────────────┐  ┌────────────┐  ┌──────────────────────┐ │
│  │FileIndexer   │  │RagSearch   │  │MCP Tools             │ │
│  │rag-inbox/    │→ │Ollama→     │  │rag_index/search/     │ │
│  │watcher       │  │OpenSearch  │  │status                │ │
│  └──────────────┘  └────────────┘  └──────────────────────┘ │
│  PostgreSQL schema: rag (indexed_documents)                   │
└──────────────────────────────────────────────────────────────┘
```

---

## Доступные функции

### JavaMailAgent
- Чтение входящей почты (Maildev / IMAP / Exchange EWS)
- Классификация писем через Claude: REQUEST / DRAFT / NOISE
- REQUEST → создание PENDING задачи в JavaMemoryService
- DRAFT → сохранение черновика в drafts/
- NOISE → пометить прочитанным, переместить в processed/
- Дедупликация через таблицу processed_emails
- Web UI: http://localhost:8080/ui/status

### JavaMemoryService
- Ежедневный план задач с приоритетами и статусами
- PENDING очередь: подтверждение / отклонение задач от агентов
- Расширенные описания задач в файлах workspace/tasks/TASK-{id}.md
- Управление инцидентами (P1/P2/P3) и рисками
- Карточки людей и хронологические заметки
- Capture Bot: приём сырых заметок → классификация в 18:00
- Usage Statistics UI: /ui/stats — показывает вопросы агенту, RAG search, задачи, captures и оценку сэкономленного времени.
- MCP Server: 14 инструментов для Claude агента
- Web UI: /ui/today, /ui/notes (Operational Notes), /ui/captures, /ui/knowledge, /ui/incidents, /ui/risks, /ui/people, /ui/stats

### JavaRagService
- Семантический поиск по Markdown-документации
- Автоиндексация папки rag-inbox/ каждые 60 секунд
- Идемпотентная переиндексация по SHA-256 хешу файла
- Валидация структуры документов (ADR, SERVICE_CARD, PROCESS, GLOSSARY)
- MCP tools: rag_index, rag_search, rag_index_directory, rag_status
- REST API для прямого доступа

---

## Пресквизиты

Java 21+, Maven 3.9+, Docker 24+, Claude Code (latest), Ollama (latest)

Проверить: java -version && mvn -version && docker --version && claude --version && ollama --version

---

## Установка

1. git clone https://github.com/andreyznsk/Leader-Role-Framework.git
2. ollama pull mxbai-embed-large
3. Скопировать application-local.yml.example → application-local.yml для каждого сервиса
4. Создать симлинки ARCHITECTURE.md в каждом сервисе
5. docker compose up -d
6. ./test-runner/build.sh

---

## Запуск

./test-runner/start-services.sh --profile local

Или вручную из корня Leader-Role-Framework/:
  SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar
  SPRING_PROFILES_ACTIVE=local java -jar JavaRagService/target/rag-service.jar
  SPRING_PROFILES_ACTIVE=local java -jar JavaMailAgent/target/mail-agent.jar

---

## Health check

./test-runner/healthcheck.sh

Web UI:
  http://localhost:8082/ui/today   — план дня
  http://localhost:8080/ui/status  — почтовый агент
  http://localhost:5601            — OpenSearch Dashboards

---

## E2E тесты

Smoke: claude --print "Прочитай test-runner/AGENT.md. Прогони CRITICAL сценарии всех сервисов."
Full:  claude --print "Прочитай test-runner/AGENT.md. Прогони JavaRagService/test_e2e/*."

JavaRagService: 44 PASS / 0 FAIL (2026-06-12)

---

## Профили

local → Maildev (Docker), разработка
dev   → IMAP, тестовый сервер
prod  → Exchange EWS, рабочий ПК


## Структура проекта

```
techlead-workspace/
│
├── CLAUDE.md                    ← Главный промпт агента (не трогай)
├── .mcp.json                    ← MCP серверы (НАСТРОЙ ТОКЕНЫ)
│
├── .claude/
│   ├── settings.json            ← Permissions и настройки
│   ├── agents/                  ← Суб-агенты
│   │   ├── arch-analyst.md      ← Строит карту архитектуры
│   │   ├── risk-scanner.md      ← Ищет риски в Jira
│   │   ├── doc-writer.md        ← Создаёт документы
│   │   └── signal-filter.md     ← Отделяет сигнал от шума
│   └── commands/                ← Slash-команды
│       ├── standup.md           ← /standup
│       └── week-review.md       ← /week-review
│
├── skills/                      ← Скиллы (промпты под задачи)
│   ├── arch-mapper.md
│   ├── people-mapper.md
│   ├── release-prep.md
│   ├── incident-playbook.md
│   ├── daily-journal.md
│   └── risk-scan.md
│
└── workspace/                   ← Твой второй мозг (сюда агент пишет)
    ├── 00_people/               ← Stakeholder Map
    ├── 01_services/             ← Карта архитектуры
    ├── 02_processes/            ← Release flow, playbook
    ├── 03_incidents/            ← Постмортемы
    ├── 04_releases/             ← Release Notes, чеклисты
    ├── 05_questions/            ← Открытые вопросы
    ├── 06_decisions/            ← ADR
    ├── 07_risks/                ← Operational risks
    └── 08_daily_journal/        ← Дневник
```
