# CR-RAG-BUGFIX-002: Добавить REST API для rag_index, rag_search, rag_status, rag_index_directory

**Дата:** 2026-06-12
**Статус:** Approved
**Сервис:** RAG
**Источник:** TEST-REPORT-2026-06-12-rag-run1.md

---

## Проблема / Мотивация

E2E тест-сценарии (`JavaRagService/test_e2e/*.md`) вызывают:
- `POST /mcp` с телом `{"method":"rag_index","params":{...}}`
- `POST /api/search` с телом `{"query":"...","top_k":3}`
- `GET /mcp/rag_status`
- `POST /mcp` с `{"method":"rag_index_directory","params":{...}}`

Все эти endpoints возвращают **HTTP 404** — REST-контроллеров нет.

RagService реализует tools через Spring AI MCP (`@Tool` аннотации).
MCP работает по SSE-протоколу (`GET /sse` → `POST /mcp/message?sessionId=...`) —
это не тестируется простым `curl` из сценариев.

Без REST API невозможно:
- прогонять E2E тесты
- интегрироваться с другими сервисами напрямую
- вызывать индексацию из скриптов CI

---

## Решение

Добавить `@RestController RagRestController` с четырьмя endpoints,
которые делегируют в существующие сервисные бины (`FileIndexer`, `RagSearchService`,
`IndexedDocumentRepository`, `RagMcpTools`).

Логика не дублируется — REST просто вызывает то же что MCP tools.

---

## Изменения в API

### POST /api/rag/index
```json
// Request
{ "file_path": "rag-inbox/my-doc.md" }

// Response 200
{ "chunks_added": 3, "status": "indexed", "file_path": "rag-inbox/my-doc.md" }

// Response 200 (skipped)
{ "chunks_added": 2, "status": "skipped", "file_path": "rag-inbox/my-doc.md" }

// Response 400 (validation fail)
{ "chunks_added": 0, "status": "invalid: Отсутствует frontmatter", "file_path": "..." }
```

### POST /api/rag/index-directory
```json
// Request
{ "dir_path": "rag-inbox/batch", "pattern": "*.md" }

// Response 200
{ "indexed": 3, "skipped": 0, "failed": 0, "message": "done" }
```

### POST /api/search
```json
// Request
{ "query": "семантический поиск", "top_k": 5 }

// Response 200
[
  { "text": "...", "source": "rag-inbox/doc.md", "score": 0.87, "chunk_index": 0 }
]
```

### GET /api/rag/status
```json
// Response 200
[
  {
    "filePath": "rag-inbox/doc.md",
    "chunkCount": 3,
    "status": "indexed",
    "indexedAt": "2026-06-12T13:09:24"
  }
]
```

---

## Изменения в коде

**Новый файл:**
`JavaRagService/src/main/java/ru/andreyz/ragservice/api/RagRestController.java`

```java
@RestController
@RequestMapping
class RagRestController {
    POST /api/rag/index       → fileIndexer.indexFile(filePath)
    POST /api/rag/index-directory → ragMcpTools.ragIndexDirectory(dirPath, pattern)
    POST /api/search          → searchService.search(query, topK)
    GET  /api/rag/status      → repository.findAll()
}
```

**Без изменений:**
- `FileIndexer`, `RagMcpTools`, `RagSearchService`, `IndexedDocumentRepository` — не трогать
- MCP tools продолжают работать параллельно

---

## Как тестировать

После применения CR прогнать:
```bash
# Healthcheck
curl http://localhost:8081/actuator/health

# Индексация
curl -s -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/test.md"}'

# Поиск
curl -s -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"тест","top_k":3}'

# Статус
curl -s http://localhost:8081/api/rag/status
```

Затем повторный прогон E2E сценариев — ожидается PASS по шагам с `curl`.

---

## Зависимости от других сервисов

Нет. Только внутренние бины RagService.
