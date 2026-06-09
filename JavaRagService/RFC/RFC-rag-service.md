# RFC: JavaRagService

**Версия:** 1.0  
**Дата:** 2026-06-08  
**Статус:** Draft  
**Автор:** Андрей Зайцев  
**Проект:** Leader-Role-Framework

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
│   └── java-rag-service.jar :8081
│
├── rag-inbox/               ← папка-приёмник документов
│   └── *.md
│
└── workspace/01_services/
    └── architecture/        ← карточки сервисов от arch-analyst
```

**Связи:**
```
Claude-агент  ──MCP tools──→  JavaRagService :8081
JavaRagService ──embeddings──→  Ollama :11434
JavaRagService ──index/search──→  OpenSearch :9200
JavaRagService ──track docs──→  PostgreSQL :5432
```

Прямых вызовов с JavaMailAgent и JavaMemoryService нет.  
Сервис полностью независим — запускается и останавливается отдельно.

---

## 3. Инфраструктура

| Компонент | Где запускается | Порт |
|-----------|----------------|------|
| JavaRagService JAR | локально (macOS M1) | 8081 |
| OpenSearch | Docker | 9200 |
| PostgreSQL | Docker (общий с JavaMemoryService) | 5432 |
| Ollama + multilingual-e5-large | локально (macOS M1, Metal) | 11434 |

**Важно:** Ollama — **не в Docker**.  
Docker на macOS — это Linux VM, Metal acceleration внутри недоступен.  
Ollama нативно на M1 даёт GPU embeddings, ~2GB RAM.

---

## 4. Компоненты сервиса

### 4.1 MCP HTTP сервер

Сервис висит как постоянный процесс на порту 8081.  
Claude-агент подключается через `.mcp.json`:

```json
{
  "mcpServers": {
    "rag": {
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

**MCP инструменты:**

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
3. Агент вызывает rag_index("rag-inbox/service-card.md")
4. Сервис индексирует немедленно, возвращает {chunks_added, status}
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

### 4.3 Indexing pipeline

```
Файл (.md)
    ↓
ChunkSplitter  →  разбить на chunks по абзацам (двойной перенос строки)
                  минимум 100 символов на chunk, максимум 1000
                  overlap: последнее предложение предыдущего chunk
    ↓
OllamaClient   →  POST http://localhost:11434/api/embeddings
                  model: multilingual-e5-large
                  → float[] vector (1024 dim)
    ↓
OpenSearchClient → PUT /rag-knowledge/_doc/{chunk_id}
                   {
                     "text": "...",
                     "vector": [...],
                     "source": "rag-inbox/adr-005.md",
                     "doc_id": "adr-005",
                     "chunk_index": 0,
                     "indexed_at": "2026-06-08T10:00:00Z"
                   }
    ↓
PostgreSQL     →  INSERT/UPDATE indexed_documents
```

**При переиндексации файла:**  
Сначала удалить все chunks с `source = file_path` из OpenSearch, затем индексировать заново.

---

### 4.4 Search pipeline

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
    {text, source, score, chunk_index}
```

---

### 4.5 PostgreSQL — таблица indexed_documents

```sql
CREATE TABLE indexed_documents (
    id           SERIAL PRIMARY KEY,
    file_path    TEXT NOT NULL UNIQUE,
    file_hash    TEXT NOT NULL,
    indexed_at   TIMESTAMP DEFAULT NOW(),
    chunk_count  INT,
    status       TEXT DEFAULT 'indexed'
    -- статусы: indexed | failed | outdated
);
```

Схема применяется при старте сервиса (Flyway или ручной скрипт в `resources/sql/`).

---

## 5. Структура проекта

```
JavaRagService/
├── pom.xml
├── ARCHITECTURE.md          ← симлинк на ../ARCHITECTURE.md
├── RFC-rag-service.md       ← этот файл
├── src/main/java/ru/andreyz/ragservice/
│   ├── RagServiceApplication.java
│   ├── mcp/
│   │   ├── McpServer.java           ← HTTP сервер MCP tools
│   │   └── RagMcpTools.java         ← реализация rag_index, rag_search, rag_status
│   ├── indexer/
│   │   ├── FileIndexer.java         ← оркестратор: файл → chunks → OS
│   │   ├── ChunkSplitter.java       ← разбивка .md на chunks
│   │   └── IndexScheduler.java      ← scheduleWithFixedDelay, ls rag-inbox/
│   ├── search/
│   │   └── RagSearchService.java    ← query → vector → kNN → results
│   ├── client/
│   │   ├── OllamaClient.java        ← POST /api/embeddings
│   │   └── OpenSearchClient.java    ← index + kNN search
│   └── db/
│       └── IndexedDocumentRepository.java  ← PostgreSQL JDBC
├── src/main/resources/
│   ├── application.properties
│   ├── application-local.properties
│   ├── application-dev.properties
│   ├── application-prod.properties
│   └── sql/
│       └── V1__create_indexed_documents.sql
└── target/
    └── rag-service.jar
```

---

## 6. Конфигурация

`application.properties` (базовые значения):
```properties
server.port=8081

ollama.url=http://localhost:11434
ollama.model=multilingual-e5-large

opensearch.url=http://localhost:9200
opensearch.index=rag-knowledge

rag.inbox.path=../rag-inbox
rag.scheduler.interval-ms=60000

spring.datasource.url=jdbc:postgresql://localhost:5432/leader_framework
spring.datasource.username=postgres
spring.datasource.password=postgres
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
<!-- HTTP сервер (без Spring если легковесный) -->
<dependency>javalin или undertow</dependency>

<!-- OpenSearch -->
<dependency>
    <groupId>org.opensearch.client</groupId>
    <artifactId>opensearch-rest-high-level-client</artifactId>
</dependency>

<!-- PostgreSQL -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>

<!-- HTTP клиент для Ollama -->
<dependency>java.net.http (встроенный)</dependency>

<!-- JSON -->
<dependency>com.fasterxml.jackson.core:jackson-databind</dependency>
```

> Spring Boot — опционально. Если хочется легковесности как в mail-agent — можно без Spring, только Javalin + JDBC.

---

## 8. Запуск

```bash
# Инфраструктура (если не запущена)
docker compose up -d opensearch postgres

# Убедиться что Ollama запущена и модель загружена
ollama run multilingual-e5-large

# Сервис
SPRING_PROFILES_ACTIVE=local java -jar JavaRagService/target/rag-service.jar
```

**Проверка:**
```bash
# Статус
curl http://localhost:8081/mcp/rag_status

# Тестовый поиск
curl -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "как проходит релиз", "top_k": 3}'
```

---

## 9. Окружения

| Профиль | rag-inbox path | OpenSearch | PostgreSQL |
|---------|---------------|------------|------------|
| `local` | `../rag-inbox` | localhost:9200 | localhost:5432 |
| `dev` | `../rag-inbox` | localhost:9200 | localhost:5432 |
| `prod` | `../rag-inbox` | localhost:9200 | localhost:5432 |

---

## 10. Что планируется (Future)

- `rag_delete(file_path)` — удалить документ из индекса
- Поддержка форматов: `.txt`, `.adoc`, `.confluence` (экспорт)
- Reranker поверх kNN результатов (cross-encoder через Ollama)
- Метрики индексации в Grafana (количество документов, latency)
- OpenSearch Dashboards — визуализация базы знаний