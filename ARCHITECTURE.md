# Leader-Role-Framework — Architecture

**Последнее обновление:** 2026-06-06  
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
│  │                 │        │  PostgreSQL (Docker :5432)   │    │
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
| `NOISE` | Пометить прочитанным, переместить в `processed/` |

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

**База данных:** PostgreSQL в Docker, порт 5432

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
Принимает запрос, ищет релевантные документы в OpenSearch, возвращает контекст.

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
# Все инфра-сервисы — в Docker
# Java-сервисы — локальные JAR (не Docker)

services:
  postgres:
    image: postgres:16
    ports: ["5432:5432"]
    # используется: JavaMemoryService

  opensearch:
    image: opensearchproject/opensearch:2
    ports: ["9200:9200"]
    # используется: JavaRagService

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2
    ports: ["5601:5601"]

  maildev:
    image: maildev/maildev:latest
    ports:
      - "1080:1080"   # Web UI + HTTP API
      - "1025:1025"   # SMTP
    # используется: JavaMailAgent (только local профиль)
```

**Ollama** — нативно на macOS (Apple Silicon M1), не в Docker.  
Модель эмбеддингов: `multilingual-e5-large`

---

## Файловая шина (Leader-Role-Framework корень)

JavaMailAgent и Claude-агент общаются через файловую систему:

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

Запускается JavaMailAgent как subprocess:
```bash
claude --print "<промпт с текстом письма>"
```

Рабочая директория запуска — корень `Leader-Role-Framework/`.  
Агент автоматически читает корневой `CLAUDE.md` как контекст.

**Возвращает** одну JSON строку в stdout:
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

## MCP серверы (`.mcp.json` в корне)

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

Запуск сервисов:
```bash
# Инфраструктура
docker compose up -d

# Java сервисы (каждый в отдельном терминале, из корня проекта)
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

Прямых вызовов между JavaRagService и JavaMemoryService нет.  
Оба доступны Claude-агенту независимо.

---

## Что ещё планируется (Future)

- **Telegram-бот** в JavaMailAgent — отчёт после каждого цикла, вопросы агента
- **SMTP отправка** — новый тип `SEND` в AgentResponseType
- **Grafana** — визуализация capacity команды из Jira + PostgreSQL
- **Obsidian** — экспорт заметок и планов
- **macOS уведомления** — `osascript` / `say` при завершении задач агентом

---

## Maven координаты

| Сервис | groupId | artifactId |
|--------|---------|------------|
| JavaMailAgent | `ru.andreyz.mailagent` | `mail-agent` |
| JavaMemoryService | `ru.andreyz.memoryservice` | `memory-service` |
| JavaRagService | `ru.andreyz.ragservice` | `rag-service` |

Пакеты Java кода следуют groupId:
```
ru.andreyz.mailagent.*
ru.andreyz.memoryservice.*
ru.andreyz.ragservice.*
```

---

## RFC документы

| Сервис | RFC | Статус |
|--------|-----|--------|
| JavaMailAgent | `JavaMailAgent/RFC-java-core.md` | ✅ Ready |
| JavaMemoryService | `JavaMemoryService/RFC-memory-service.md` | ⬜ Нужно создать |
| JavaRagService | `JavaRagService/RFC-rag-service.md` | ⬜ Нужно создать |

---

## Симлинки на этот файл

Каждый сервис имеет симлинк `ARCHITECTURE.md → ../ARCHITECTURE.md`:

```bash
cd JavaMailAgent   && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
cd ../JavaMemoryService && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
cd ../JavaRagService    && ln -s ../ARCHITECTURE.md ARCHITECTURE.md
```

Симлинки закоммичены в git — работают после `git clone` на любой машине.
