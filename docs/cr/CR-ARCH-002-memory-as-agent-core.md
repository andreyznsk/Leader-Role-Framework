# CR-ARCH-002: Memory Service как единая точка входа AI-агента и фасад к RAG

**Дата:** 2026-06-14  
**Статус:** Draft  
**Сервис:** ARCH / MEM / RAG / TEST  
**Зависимости:** JavaMemoryService, JavaRagService, MCP tools, e2e tests  
**Предшествует:** `CR-MEM-003-usage-statistics.md`

---

## 1. Проблема / мотивация

Сейчас `JavaRagService` имеет собственные REST API и MCP tools (`rag_index`, `rag_search`, `rag_index_directory`, `rag_status`), а `JavaMemoryService` имеет собственные MCP tools для задач, рисков, людей и контекста.

Это делает архитектуру менее продуктовой:

- AI-агент должен знать несколько MCP-серверов;
- статистику использования сложно собирать в одном месте;
- сценарий "задать вопрос своему агенту" размазывается между Memory и RAG;
- будущие источники знаний (`mail`, `jira`, `calendar`, `confluence`, `meetings`) будут увеличивать число прямых интеграций агента.

Нужно зафиксировать новую архитектурную роль:

> **JavaMemoryService = LeaderOS Core / единая точка входа AI-агента.**  
> `JavaRagService` становится внутренним knowledge backend / модулем поиска, а не основным интерфейсом для внешнего агента.

---

## 2. Целевое решение

Внешний AI-агент должен работать только с MCP-интерфейсом `JavaMemoryService`.

`JavaMemoryService` агрегирует:

- оперативный контекст: задачи, риски, инциденты, заметки, люди;
- стабильные знания: через `JavaRagService`;
- в будущем: почта, Jira, Confluence, календарь, встречи.

Целевой flow:

```text
User question
    ↓
AI Agent
    ↓
Memory MCP: searchKnowledge / ask / getContext
    ↓
JavaMemoryService
    ├─ local operational context: tasks, risks, incidents, people, notes
    └─ JavaRagService: POST /api/search
    ↓
Unified context response with sources
    ↓
AI Agent final answer
```

---

## 3. Архитектурное правило

Добавить в `ARCHITECTURE.md` раздел:

```markdown
## Agent Access Rule

External AI agents must use JavaMemoryService as the only MCP entrypoint.

JavaRagService remains an internal knowledge backend. Direct calls to JavaRagService MCP tools from external agents are deprecated for product scenarios.

Allowed:
Agent → JavaMemoryService MCP → JavaRagService REST

Deprecated:
Agent → JavaRagService MCP
Agent → multiple independent MCP servers for core LeaderOS flows
```

---

## 4. Изменения в JavaMemoryService

### 4.1. Новый REST client к JavaRagService

Добавить компонент:

```java
RagServiceClient
```

Ответственность:

- вызывать `POST {rag.base-url}/api/search`;
- принимать query, topK, optional filters;
- возвращать нормализованные результаты поиска;
- обрабатывать недоступность RAG без падения Memory Service.

Конфигурация:

```yaml
leaderos:
  rag:
    base-url: http://localhost:8081
    default-top-k: 5
    timeout-ms: 5000
```

### 4.2. Новая доменная модель результата поиска

Добавить DTO:

```java
KnowledgeSearchRequest
KnowledgeSearchResponse
KnowledgeSearchResult
KnowledgeSourceType
```

Пример `KnowledgeSourceType`:

```java
public enum KnowledgeSourceType {
    RAG_DOCUMENT,
    TASK,
    NOTE,
    INCIDENT,
    RISK,
    PERSON,
    MAIL,
    MEETING,
    JIRA,
    CONFLUENCE
}
```

MVP использует:

- `RAG_DOCUMENT`
- `TASK`
- `NOTE`
- `INCIDENT`
- `RISK`
- `PERSON`

Остальные типы оставить как задел на будущее.

### 4.3. Новый REST endpoint Memory Service

Добавить endpoint:

```http
POST /api/knowledge/search
```

Request:

```json
{
  "query": "как у нас проходит релиз?",
  "keywords": ["релиз", "release", "prod"],
  "topK": 5,
  "includeOperationalContext": true
}
```

Response:

```json
{
  "query": "как у нас проходит релиз?",
  "results": [
    {
      "sourceType": "RAG_DOCUMENT",
      "title": "release-process.md",
      "text": "...",
      "source": "JavaRagService/rag-inbox/process/release-process.md",
      "score": 0.82
    }
  ],
  "operationalContext": {
    "tasks": [],
    "risks": [],
    "incidents": [],
    "peopleNotes": []
  }
}
```

### 4.4. Новый MCP tool в Memory Service

Добавить MCP tool:

```text
searchKnowledge
```

Назначение:

- основной инструмент агента для поиска знаний;
- внутри вызывает `/api/knowledge/search` или соответствующий service-layer метод;
- возвращает единый payload: RAG results + operational context.

Описание tool для агента:

```text
Search LeaderOS knowledge and operational memory. Use this tool when the user asks a question about architecture, processes, risks, tasks, people, incidents, documentation or project knowledge.
```

---

## 5. Изменения в JavaRagService

`JavaRagService` не удаляется и не упрощается.

Он остаётся владельцем:

- индексации документов;
- OpenSearch;
- embeddings через Ollama;
- REST API `/api/search`;
- собственных технических e2e тестов.

Но его MCP tools нужно пометить как deprecated для внешнего продуктового сценария.

В RFC `JavaRagService/RFC/RFC-rag-service.md` добавить:

```markdown
## Agent Access

JavaRagService MCP tools are technical/internal tools.

Product AI agents should not call JavaRagService directly. They must call JavaMemoryService MCP `searchKnowledge`, which delegates search to JavaRagService and enriches results with operational context.
```

---

## 6. Изменения в RFC и мастер-спеке

Обязательно обновить:

### 6.1. `ARCHITECTURE.md`

Изменить схему связей:

Было:

```text
Claude Agent ← MCP → JavaRagService
Claude Agent ← MCP → JavaMemoryService
```

Стало:

```text
AI Agent ← MCP → JavaMemoryService ← REST → JavaRagService
```

Добавить:

- `JavaMemoryService` как `LeaderOS Core`;
- `JavaRagService` как `Knowledge Backend`;
- правило единого MCP entrypoint;
- новый endpoint `/api/knowledge/search`;
- новый MCP tool `searchKnowledge`.

### 6.2. `JavaMemoryService/RFC/RFC-memory-service.md`

Добавить:

- раздел `Knowledge Gateway`;
- REST endpoint `/api/knowledge/search`;
- MCP tool `searchKnowledge`;
- `RagServiceClient`;
- описание отказоустойчивости при недоступности RAG.

### 6.3. `JavaRagService/RFC/RFC-rag-service.md`

Добавить:

- раздел `Internal Knowledge Backend`;
- пометку, что прямой MCP доступ к RAG deprecated для внешнего агента;
- контракт интеграции с Memory Service.

### 6.4. `README.md`

Обновить краткое описание архитектуры:

- агент работает через Memory Service;
- Memory Service вызывает RAG Service;
- RAG не является прямым пользовательским интерфейсом агента.

---

## 7. E2E тесты, которые нужно поправить

Важно: в проекте уже есть e2e тесты, их нужно привести к новой архитектуре.

### 7.1. Добавить MemoryService e2e

Новый сценарий:

```text
JavaMemoryService/test_e2e/12_knowledge_search_gateway.md
```

Проверяет:

1. `JavaMemoryService` запущен.
2. `JavaRagService` запущен.
3. Тестовый markdown-документ индексируется через RAG API.
4. Запрос отправляется в Memory:

```bash
curl -s -X POST "$MS_URL/api/knowledge/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"e2e knowledge gateway unique marker","topK":5,"includeOperationalContext":true}'
```

5. Response содержит:
   - `results`;
   - `sourceType = RAG_DOCUMENT`;
   - уникальный маркер из тестового документа.

### 7.2. Добавить MCP e2e

Расширить:

```text
JavaMemoryService/test_e2e/09_mcp_tools.md
```

Проверить, что tool list содержит:

```text
searchKnowledge
```

И что его можно вызвать через MCP handshake / tool call flow.

### 7.3. Поправить RAG e2e документацию

RAG e2e остаются валидными, но в описаниях нужно явно указать:

```markdown
These tests validate JavaRagService as an internal knowledge backend.
Product agent flow is tested through JavaMemoryService/test_e2e/12_knowledge_search_gateway.md.
```

### 7.4. Добавить интеграционный тест

Новый сценарий:

```text
e2e-integration/07_agent_memory_to_rag_search.md
```

Проверяет сквозной flow:

```text
document → JavaRagService index → MemoryService searchKnowledge → unified response
```

### 7.5. Обновить test-runner/AGENT.md

Добавить правило:

```markdown
For product-level knowledge search scenarios, test through JavaMemoryService.
Do not treat direct JavaRagService MCP tool calls as the primary agent flow.
```

---

## 8. Acceptance Criteria

CR считается выполненным, если:

- [ ] `ARCHITECTURE.md` обновлён и отражает Memory Service как единый MCP entrypoint.
- [ ] `JavaMemoryService/RFC/RFC-memory-service.md` обновлён.
- [ ] `JavaRagService/RFC/RFC-rag-service.md` обновлён.
- [ ] `README.md` обновлён.
- [ ] В `JavaMemoryService` добавлен `RagServiceClient`.
- [ ] Добавлен endpoint `POST /api/knowledge/search`.
- [ ] Добавлен MCP tool `searchKnowledge`.
- [ ] При недоступном RAG Memory Service возвращает корректную деградацию, а не 500 без объяснения.
- [ ] Добавлен e2e `JavaMemoryService/test_e2e/12_knowledge_search_gateway.md`.
- [ ] Обновлён `JavaMemoryService/test_e2e/09_mcp_tools.md`.
- [ ] Добавлен `e2e-integration/07_agent_memory_to_rag_search.md`.
- [ ] Обновлён `test-runner/AGENT.md`.
- [ ] Все существующие критичные e2e тесты проходят.

---

## 9. Как тестировать

```bash
docker compose up -d
./test-runner/build.sh
./test-runner/start-services.sh --profile local
./test-runner/healthcheck.sh
```

Ручная проверка:

```bash
curl -s -X POST http://localhost:8082/api/knowledge/search \
  -H "Content-Type: application/json" \
  -d '{"query":"release process","topK":5,"includeOperationalContext":true}' | jq
```

Ожидается:

- HTTP 200;
- поле `results`;
- поле `operationalContext`;
- если RAG недоступен — response не падает неуправляемо, а содержит диагностическое сообщение.

E2E:

```bash
source e2e-integration/env.sh
# Прогнать:
# JavaMemoryService/test_e2e/12_knowledge_search_gateway.md
# e2e-integration/07_agent_memory_to_rag_search.md
```

---

## 10. Риски и ограничения

- Не переносить индексирование документов в Memory Service.
- Не удалять REST API RAG Service.
- Не ломать технические RAG e2e тесты.
- Не добавлять прямой доступ Memory Service к OpenSearch.
- Memory Service должен вызывать RAG только через публичный REST API JavaRagService.
- Если RAG недоступен, агент всё равно должен получить operational context из Memory.
