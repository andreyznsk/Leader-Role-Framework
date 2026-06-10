# Leader-Role-Framework — Architecture - Мастер-Спека

**Последнее обновление:** 2026-06-10  
**Статус:** Living document — обновлять при любом изменении контрактов между сервисами

---

## Обзор системы

AI-powered фреймворк техлида. Автоматизирует рутину: обработку почты,
ведение плана дня, работу с документацией, мониторинг команды.

```
┌─────────────────────────────────────────────────────────────────┐
│                    Leader-Role-Framework                         │
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
│   └── today.md        ← план дня, агент дописывает REQUEST задачи
└── workspace/          ← рабочее пространство агента
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
claude --print ──читает──→  JavaRagService (через MCP или HTTP /api/search)
```

---

## Что ещё планируется (Future)

- **Chat-бот** в JavaMailAgent
- **SMTP отправка** — тип `SEND`
- **Grafana** — capacity из Jira + PostgreSQL
- **Obsidian** — экспорт заметок
- **macOS уведомления** — `osascript`

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
| JavaMailAgent | `JavaMailAgent/RFC-java-core.md` | ✅ Ready |
| JavaMemoryService | `JavaMemoryService/RFC-memory-service.md` | ⬜ Нужно создать |
| JavaRagService | `JavaRagService/RFC-rag-service.md` | ⬜ Нужно создать |

---

## CR (Change Request) Workflow

Любое изменение в сервисе оформляется через CR — это сохраняет историю решений.

### Структура папок

```
JavaMemoryService/
├── RFC/
     └── [RFC-memory-service.md](JavaMemoryService/RFC/RFC-memory-service.md)     ← главная спека (живой документ)
└── cr/
    ├── CR-001-capture-bot.md
    ├── CR-002-scheduler.md
    └── ...

JavaRagService/
├── RFC/
      └── [RFC-rag-service.md](JavaRagService/RFC/RFC-rag-service.md)
└── cr/
    └── ...

JavaMailAgent/
├── RFC/
      └── [RFC-JavaMailAgent.md](JavaMailAgent/RFC/RFC-JavaMailAgent.md)
└── cr/
    └── ...
```

### Процесс

```
1. Новая идея / фича
        ↓
2. Создать CR-XXX.md в cr/ нужного сервиса
        ↓
3. Агент читает CR → вносит изменения в RFC (главную спеку)
        ↓
4. CR остаётся как история (не удалять)
```

### Шаблон CR файла

# CR-XXX: Название изменения

**Дата:** YYYY-MM-DD
**Статус:** Draft | Review | Approved | Implemented
**Сервис:** JavaMemoryService | JavaRagService | JavaMailAgent
**Зависимости:** (другие сервисы, инфраструктура)

## Проблема / Мотивация
Что не работает или чего не хватает.

## Решение
Верхнеуровневое описание что делаем.

## Изменения в API
| Метод | Путь | Описание |
|-------|------|----------|
| POST | /api/new-endpoint | ... |

## Изменения в схеме БД
ALTER TABLE ...

## Зависимости от других сервисов


## Как тестировать

## Симлинки на этот файл

Каждый сервис имеет симлинк `ARCHITECTURE.md → ../ARCHITECTURE.md`:

```bash
cd JavaMailAgent        && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
cd ../JavaMemoryService && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
cd ../JavaRagService    && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
```