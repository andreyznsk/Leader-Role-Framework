# RFC: JavaRagService

**Версия:** 1.3
**Дата:** 2026-06-12
**Статус:** Active
**Автор:** Андрей Зайцев
**Проект:** Leader-Role-Framework

---

## Changelog

| Версия | Дата | CR | Что изменилось |
|--------|------|----|----------------|
| 1.0 | 2026-06-08 | — | Первая версия |
| 1.1 | 2026-06-11 | CR-RAG-001 | PostgreSQL схема `rag`, Flyway миграции, профиль `local` |
| 1.2 | 2026-06-11 | CR-RAG-002 | DocumentValidator, `invalid` статус, `error_message` в таблице |
| 1.3 | 2026-06-12 | CR-RAG-BUGFIX-002, CR-RAG-E2E-001 | REST API (`RagRestController`), поле `invalid` в DirectoryIndexResult, актуальные URL |

---

## 1. Назначение

JavaRagService — самостоятельный Java-сервис (отдельный JAR, отдельный проект).
Реализует RAG (Retrieval-Augmented Generation) — семантическую базу знаний техлида.

**Что хранит:** стабильные текстовые документы:
- архитектурные схемы (ADR, C4, карточки сервисов)
- процессы команды (release flow, onboarding, runbooks)
- глоссарий сокращений
- Markdown-артефакты из workspace

**Что НЕ хранит** (→ это в PostgreSQL / JavaMemoryService):
- задачи, планы дня
- состояние писем
- инциденты (оперативные)
- всё что меняется чаще раза в день

---

## 2. Место в архитектуре

```
Leader-Role-Framework/
├── JavaRagService/          ← этот сервис
│   └── rag-service.jar :8081
│
├── rag-inbox/               ← папка-приёмник документов
│   └── *.md
│
└── workspace/01_services/
    └── architecture/        ← карточки сервисов от arch-analyst
```

**Связи:**
```
Claude-агент  ──MCP (Spring AI)──→  JavaRagService :8081
Claude-агент  ──REST API────────→  JavaRagService :8081
JavaRagService ──embeddings──→  Ollama :11434
JavaRagService ──index/search──→  OpenSearch :9200 (172.80.2.1 в local)
JavaRagService ──track docs──→  PostgreSQL :5432 (схема: rag)
```

Прямых вызовов с JavaMailAgent и JavaMemoryService нет.
Сервис полностью независим — запускается и останавливается отдельно.

---

## 3. Инфраструктура

| Компонент | Где запускается | Адрес (local profile) |
|-----------|----------------|----------------------|
| JavaRagService JAR | локально | localhost:8081 |
| OpenSearch | Docker | 172.80.2.1:9200 |
| PostgreSQL | Docker (общий с JavaMemoryService) | localhost:5432 |
| Ollama + mxbai-embed-large | локально (Metal) | localhost:11434 |

> **Важно:** OpenSearch внутри Docker доступен по `172.80.2.1:9200` (bridge IP), а не по `localhost:9200`. `localhost:9200` не работает с хоста в текущей конфигурации.

> **Ollama — не в Docker.** Docker на macOS — это Linux VM, Metal acceleration внутри недоступен. Ollama нативно на M1 даёт GPU embeddings, ~2GB RAM.

---

## 4. Компоненты сервиса

### 4.1 API — REST + MCP

Сервис предоставляет **два** интерфейса доступа к одной и той же логике.

#### REST API (`RagRestController`)

Добавлен в **CR-RAG-BUGFIX-002**. Основной интерфейс для E2E тестов, скриптов и curl.

| Method | Endpoint | Тело запроса | Ответ |
|--------|----------|--------------|-------|
| POST | `/api/rag/index` | `{"file_path": "..."}` | `{"chunksAdded": N, "status": "indexed\|skipped\|invalid", "filePath": "..."}` |
| POST | `/api/rag/index-directory` | `{"dir_path": "...", "pattern": "*.md"}` | `{"indexed": N, "skipped": N, "failed": N, "invalid": N, "message": "done"}` |
| POST | `/api/search` | `{"query": "...", "top_k": N}` | `[{"text":"...","source":"...","score":0.87,"chunkIndex":0}]` |
| GET | `/api/rag/status` | — | `[{"filePath":"...","chunkCount":N,"status":"...","indexedAt":"..."}]` |

> **Примечание:** поля ответа в camelCase (Jackson default) — `chunksAdded`, `filePath`, `chunkCount`, `indexedAt`.

#### MCP tools (Spring AI)

Сервис регистрирует Spring AI MCP сервер. Claude-агент подключается через `.mcp.json`:

```json
{
  "mcpServers": {
    "rag": {
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

| Tool | Параметры | Описание |
|------|-----------|----------|
| `rag_index` | `file_path: String` | Индексировать один файл немедленно |
| `rag_index_directory` | `dir_path: String, pattern: String = "*.md"` | Сканировать папку, пропустить уже проиндексированные |
| `rag_search` | `query: String, top_k: Int = 5` | Семантический поиск, вернуть top-K chunks |
| `rag_status` | — | Список всех документов из PostgreSQL + статус |

**Типичный сценарий — агент сохраняет знание:**
```
1. Агент генерирует service-card.md
2. Агент кладёт файл в rag-inbox/
3. Агент вызывает POST /api/rag/index {"file_path":"rag-inbox/service-card.md"}
   или MCP tool rag_index("rag-inbox/service-card.md")
4. Сервис индексирует немедленно, возвращает {chunksAdded, status}
```

**Типичный сценарий — ручное добавление документа:**
```
1. Ты кладёшь adr-005.md в rag-inbox/
2. Scheduler через ≤1 минуту находит новый файл
3. Индексирует автоматически, без вмешательства
```

---

### 4.2 Scheduler (file watcher)

Запускается внутри того же JAR через `scheduleWithFixedDelay`.
Один поток — исключает конкурентную индексацию.

**Алгоритм каждую минуту:**
```
1. ls rag-inbox/**/*.md
2. Для каждого файла: вычислить SHA-256 hash
3. SELECT file_hash FROM indexed_documents WHERE file_path = ?
4. Если hash совпадает → пропустить (idempotent)
5. Если файл новый или hash изменился → индексировать
6. Обновить запись в PostgreSQL
```

---

### 4.3 Валидация документов (DocumentValidator)

Добавлена в **CR-RAG-002**. Выполняется **до** индексации.

**Обязательное требование для всех документов:**
```yaml
---
type: ADR          # одно из: ADR, SERVICE_CARD, PROCESS, GLOSSARY
title: ...
status: active
updated: YYYY-MM-DD
---
```

**Схемы по типу:**

| Тип | Обязательные поля frontmatter | Обязательные секции |
|-----|-------------------------------|---------------------|
| `ADR` | type, updated | `## Статус`, `## Контекст`, `## Решение`, `## Последствия` |
| `SERVICE_CARD` | type, service, updated, review_by | `## Назначение`, `## Стек`, `## Интеграции`, `## Деплой` |
| `PROCESS` | type, updated, review_by | `## Когда использовать`, `## Шаги`, `## Кто участвует`, `## Escalation` |
| `GLOSSARY` | type, updated | `# Глоссарий` |

**При ошибке валидации:**
- файл записывается в `indexed_documents` со статусом `invalid`
- поле `error_message` содержит описание ошибок
- индексация в OpenSearch не выполняется
- предупреждение в лог, обработка других файлов продолжается

---

### 4.4 Indexing pipeline

```
Файл (.md)
    ↓
DocumentValidator → проверить frontmatter и структуру заголовков
                    если невалиден → статус invalid + error_message в PostgreSQL, стоп
    ↓
ChunkSplitter  →  разбить на chunks по абзацам (двойной перенос строки)
                  минимум 100 символов на chunk, максимум 1000
                  overlap: последнее предложение предыдущего chunk
    ↓
OllamaClient   →  POST http://localhost:11434/api/embeddings
                  model: mxbai-embed-large (1024 dim)
                  → float[] vector
    ↓
OpenSearchClient → PUT /rag-knowledge/_doc/{chunk_id}
                   {
                     "text": "...",
                     "vector": [...],
                     "source": "rag-inbox/adr-005.md",
                     "doc_id": "adr-005",
                     "chunk_index": 0,
                     "indexed_at": "2026-06-12T10:00:00Z"
                   }
    ↓
PostgreSQL     →  INSERT/UPDATE indexed_documents (status=indexed)
```

**При переиндексации файла:**
Сначала удалить все chunks с `source = file_path` из OpenSearch, затем индексировать заново.

---

### 4.5 Search pipeline

```
query (String)
    ↓
OllamaClient   →  POST /api/embeddings → float[] queryVector
    ↓
OpenSearchClient → kNN query:
                   {
                     "knn": {
                       "vector": {
                         "vector": queryVector,
                         "k": top_k
                       }
                     }
                   }
    ↓
Результат: List<SearchResult>
    {text, source, score, chunkIndex}
```

---

### 4.6 PostgreSQL — схема и таблица indexed_documents

Сервис использует изолированную схему `rag` в общей БД `leader_framework`.
Владелец схемы — пользователь `rag_user` с `search_path = rag`.

```sql
-- V1__init_rag_schema.sql
CREATE TABLE IF NOT EXISTS indexed_documents (
    id            SERIAL PRIMARY KEY,
    file_path     TEXT        NOT NULL UNIQUE,
    file_hash     TEXT        NOT NULL,
    indexed_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    chunk_count   INT,
    status        TEXT        NOT NULL DEFAULT 'indexed'
);

CREATE INDEX IF NOT EXISTS idx_indexed_documents_file_path ON indexed_documents(file_path);
CREATE INDEX IF NOT EXISTS idx_indexed_documents_status    ON indexed_documents(status);

-- V2__add_error_message.sql
ALTER TABLE indexed_documents
    ADD COLUMN IF NOT EXISTS error_message TEXT;
```

Миграции применяются автоматически при старте через Flyway (только схема `rag`).

**Статусы документа:**

| Статус | Когда | chunk_count | error_message |
|--------|-------|-------------|---------------|
| `indexed` | успешно проиндексирован | > 0 | null |
| `invalid` | не прошёл валидацию структуры | 0 | описание ошибок |
| `failed` | ошибка при индексации (Ollama/OpenSearch недоступен) | 0 | текст исключения |
| `outdated` | файл изменился, идёт переиндексация | старый | null |

---

## 5. Структура проекта

```
JavaRagService/
├── pom.xml
├── RFC/
│   └── RFC-rag-service.md       ← этот файл
├── cr/
│   ├── CR-RAG-001-postgres-schema.md
│   ├── CR-RAG-002-document-validation.md
│   ├── CR-RAG-BUGFIX-002-rest-api.md
│   └── CR-RAG-E2E-001.md
├── test_e2e/                    ← E2E сценарии (bash + curl)
│   ├── env.sh
│   ├── 01_health_check.md
│   ├── 02_index_and_search.md
│   ├── 02_index_single_document.md
│   ├── 03_semantic_search.md
│   ├── 04_scheduler_auto_index.md
│   ├── 05_index_directory.md
│   └── 06_reindex_on_change.md
├── src/main/java/ru/andreyz/ragservice/
│   ├── RagServiceApplication.java
│   ├── api/
│   │   └── RagRestController.java   ← REST: /api/rag/index, /api/search, /api/rag/status
│   ├── mcp/
│   │   └── RagMcpTools.java         ← MCP tools: rag_index, rag_search, rag_status
│   │                                   DirectoryIndexResult: indexed, skipped, failed, invalid
│   ├── validation/
│   │   ├── DocType.java             ← enum: SERVICE_CARD, PROCESS, GLOSSARY, ADR
│   │   ├── DocField.java            ← enum обязательных полей frontmatter
│   │   ├── DocSchema.java           ← маппинг тип → поля + секции
│   │   ├── ValidationResult.java    ← record: valid, docType, errors
│   │   └── DocumentValidator.java   ← @Component: frontmatter + структура
│   ├── indexer/
│   │   ├── FileIndexer.java         ← оркестратор: валидация → chunks → OS
│   │   │                               IndexResult: chunksAdded, status, filePath
│   │   ├── ChunkSplitter.java       ← разбивка .md на chunks
│   │   └── IndexScheduler.java      ← scheduleWithFixedDelay, ls rag-inbox/
│   ├── search/
│   │   ├── RagSearchService.java    ← query → vector → kNN → results
│   │   └── SearchResult.java        ← record: text, source, score, chunkIndex
│   ├── client/
│   │   ├── OllamaClient.java        ← POST /api/embeddings
│   │   └── OpenSearchClient.java    ← index + kNN search + deleteBySource
│   └── db/
│       ├── IndexedDocument.java     ← record: id, filePath, fileHash, indexedAt, chunkCount, status, errorMessage
│       └── IndexedDocumentRepository.java
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml        ← OpenSearch: 172.80.2.1:9200
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── db/migration/
│       ├── V1__init_rag_schema.sql
│       └── V2__add_error_message.sql
└── target/
    └── rag-service.jar
```

---

## 6. Конфигурация

`application.yml` (базовые значения):
```yaml
server:
  port: 8081

ollama:
  url: http://localhost:11434
  model: mxbai-embed-large

opensearch:
  url: http://localhost:9200
  index: rag-knowledge

rag:
  inbox:
    path: ../rag-inbox
  scheduler:
    interval-ms: 60000

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/leader_framework
    username: rag_user
    password: rag_password
    hikari:
      connection-init-sql: SET search_path TO rag
  flyway:
    schemas: rag
    default-schema: rag
    locations: classpath:db/migration
```

`application-local.yml` — переопределяет только отличия:
```yaml
opensearch:
  url: http://172.80.2.1:9200   # Docker bridge IP — localhost:9200 не работает

spring:
  datasource:
    hikari:
      maximum-pool-size: 3

logging:
  level:
    ru.andreyz.ragservice: DEBUG
```

---

## 7. Maven координаты

```xml
<groupId>ru.andreyz.ragservice</groupId>
<artifactId>rag-service</artifactId>
<version>1.0.0-SNAPSHOT</version>
```

**Ключевые зависимости:**
```xml
<!-- Spring Boot -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Spring AI MCP -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-mcp-server-spring-boot-starter</artifactId>
</dependency>

<!-- OpenSearch -->
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-rest-high-level-client</artifactId>
</dependency>

<!-- PostgreSQL + Flyway -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>

<!-- JSON -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

---

## 8. Запуск

```bash
# Инфраструктура (если не запущена)
docker compose up -d

# Убедиться что Ollama запущена
ollama list | grep mxbai-embed-large

# Сборка
./test-runner/build.sh --service JavaRagService

# Запуск
./test-runner/start-services.sh --service JavaRagService

# Или вручную:
SPRING_PROFILES_ACTIVE=local java -jar JavaRagService/target/rag-service.jar
```

**Проверка:**
```bash
# Health
curl http://localhost:8081/actuator/health

# Статус документов
curl http://localhost:8081/api/rag/status | jq 'length'

# Индексация файла
curl -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path": "rag-inbox/my-doc.md"}'

# Семантический поиск
curl -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "как проходит релиз", "top_k": 3}'
```

---

## 9. Окружения

| Профиль | rag-inbox path | OpenSearch | PostgreSQL |
|---------|---------------|------------|------------|
| `local` | `../rag-inbox` | **172.80.2.1:9200** | localhost:5432 |
| `dev` | `../rag-inbox` | localhost:9200 | localhost:5432 |
| `prod` | `../rag-inbox` | localhost:9200 | localhost:5432 |

---

## 10. E2E тестирование

**Запуск всех тестов JavaRagService:**
```bash
# Инструкция — test-runner/AGENT.md
# Сценарии — JavaRagService/test_e2e/*.md (7 сценариев, 44 шага)

# Статус прогона 2026-06-12: 44 PASS / 0 FAIL / 0 SKIP
```

**Сценарии:**
| Файл | Приоритет | Проверяет |
|------|-----------|-----------|
| `01_health_check.md` | CRITICAL | health, OpenSearch, Ollama, PostgreSQL |
| `02_index_and_search.md` | HIGH | базовый цикл: создать → проиндексировать → найти |
| `02_index_single_document.md` | HIGH | полный цикл с проверкой PostgreSQL и OpenSearch |
| `03_semantic_search.md` | HIGH | релевантность поиска, top_k |
| `04_scheduler_auto_index.md` | HIGH | автоиндексация, переиндексация при изменении |
| `05_index_directory.md` | MEDIUM | batch-индексация, фильтр .txt, идемпотентность |
| `06_reindex_on_change.md` | MEDIUM | удаление старых чанков, обновление hash |

---

## 11. Что планируется (Future)

- `DELETE /api/rag/index` — удалить документ из индекса
- Поддержка форматов: `.txt`, `.adoc`, `.confluence` (экспорт)
- Reranker поверх kNN результатов (cross-encoder через Ollama)
- Метрики индексации в Grafana (количество документов, latency)
- OpenSearch Dashboards — визуализация базы знаний
- Swagger UI (`/swagger-ui`) для REST API
