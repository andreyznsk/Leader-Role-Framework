# TEST-REPORT-2026-06-12-rag-run1

**Запуск:** 2026-06-12 11:45 — 12:10
**Профиль:** local
**Инициатор:** ручной запуск
**Агент:** Claude Sonnet 4.6

---

## Summary

| Сценарий | Приоритет | PASS | FAIL | Примечание |
|----------|-----------|------|------|------------|
| 01_health_check | CRITICAL | 2/6 | 4/6 | psql нет, /mcp/rag_status 404, Ollama модель не та |
| 02_index_and_search | HIGH | 1/3 | 2/3 | /mcp endpoint не REST, /api/search 404 |
| 02_index_single_document | HIGH | 1/6 | 5/6 | те же причины + вектор mismatch |
| 03_semantic_search | HIGH | 2/7 | 5/7 | нет REST /api/search, индексация не работает |
| 04_scheduler_auto_index | HIGH | 2/8 | 6/8 | scheduler работает, но валидация блокирует все тест-файлы + вектор mismatch |
| 05_index_directory | MEDIUM | 1/6 | 5/6 | /mcp REST endpoint отсутствует |
| 06_reindex_on_change | MEDIUM | 1/8 | 7/8 | те же причины |
| **Итого** | | **10** | **34** | |

---

## Инфраструктура — фактическое состояние

| Компонент | Статус | Детали |
|-----------|--------|--------|
| JavaRagService :8081 | ✅ UP | actuator/health → 200 |
| PostgreSQL | ✅ OK | docker exec работает; psql на хосте отсутствует |
| OpenSearch | ✅ OK | статус yellow (1 узел), через `172.80.2.1:9200` |
| Ollama | ✅ UP | модели: glm-4.7-flash, qwen3:8b, qwen2.5-coder:14b |
| OpenSearch rag-knowledge индекс | ✅ существует | dimension=1024 |
| multilingual-e5-large | ❌ НЕТ | не загружена в Ollama |

---

## 01_health_check

### Step 1 — JavaRagService health ✅ PASS
HTTP 200, actuator/health UP

### Step 2 — OpenSearch доступен ⚠️ PARTIAL PASS
Сценарий проверяет `$OPENSEARCH_URL` = `http://localhost:9200`, но snap Docker не отвечает на localhost.
Правильный адрес: `http://172.80.2.1:9200`. Статус кластера: **yellow** (1 unassigned shard — норма для 1 узла).

Исправление в env.sh: `OPENSEARCH_URL=http://172.80.2.1:9200`

### Step 3 — Ollama модель ❌ FAIL
**Expected:** список содержит `multilingual-e5-large`
**Actual:** `["glm-4.7-flash:latest", "qwen3:8b", "qwen2.5-coder:14b"]`
Модель не загружена. Профиль local использует `qwen2.5-coder:14b`.

### Step 4 — PostgreSQL indexed_documents ❌ FAIL
**Expected:** команда выполнена без ошибки
**Actual:** `psql: command not found` — psql не установлен на хосте.
**Workaround:** `docker exec leader-postgres psql -U rag_user -d leader_framework -c "..."`
Таблица `rag.indexed_documents` существует, count=0.

### Step 5 — OpenSearch индекс ✅ PASS
`GET /rag-knowledge` → HTTP 200, индекс существует.

### Step 6 — rag_status endpoint ❌ FAIL
**Expected:** HTTP 200, валидный JSON
**Actual:** HTTP 404 — `/mcp/rag_status` не существует
RagService реализует tools как Spring AI MCP (@Tool аннотации), не REST endpoints.
Реальный MCP endpoint: `GET /sse` (SSE) → `/mcp/message?sessionId=...` (POST).

---

## 02_index_and_search / 02_index_single_document

### Step 1 — Создать файл ✅ PASS (оба сценария)
Файлы создаются корректно.

### Step 2 — rag_index через MCP ❌ FAIL (оба сценария)
**Expected:** `POST /mcp` с `{"method":"rag_index",...}` → HTTP 200
**Actual:** HTTP 404 — `/mcp` не является REST endpoint

**Реальный протокол:**
```
GET /sse → event: endpoint\ndata: /mcp/message?sessionId=<UUID>
POST /mcp/message?sessionId=<UUID> → ответ через SSE stream
```
MCP вызов через сессию технически работает (4 tools зарегистрированы),
но требует постоянного SSE соединения — не тестируемо через простой curl.

### Step 3 — Семантический поиск ❌ FAIL
**Expected:** `POST /api/search` → HTTP 200
**Actual:** HTTP 404 — REST контроллер поиска отсутствует.
Поиск доступен только через MCP tool `rag_search`.

---

## 04_scheduler_auto_index

### Step 1 — indexed_documents count ✅ PASS
`COUNT = 0` (через docker exec) — корректно.

### Step 2 — Создать файл ✅ PASS
Файл создан в `rag-inbox/`.

### Step 3-7 — Ожидание индексации ❌ FAIL (3 варианта)

**Попытка 1** — файл без frontmatter:
```
WARN: Отсутствует frontmatter (файл должен начинаться с ---)
```

**Попытка 2** — frontmatter `type: runbook`:
```
WARN: Неизвестный тип документа: 'runbook'. Допустимые: [SERVICE_CARD, PROCESS, GLOSSARY, ADR]
```

**Попытка 3** — полный ADR формат (type, title, status, updated, ## Статус, ## Контекст, ## Последствия):
Валидация прошла, но индексация упала:
```
ERROR: ❌ Indexing error — Failed to index document chunk: e2e-test-full-adr_0
```

**Корневая причина:** векторная размерность mismatch:
- `qwen2.5-coder:14b` через Ollama `/api/embeddings` → **5120 dim**
- OpenSearch `rag-knowledge` kNN index → **dimension: 1024**

Scheduler работает корректно (файл обнаруживается, валидируется).
Сам механизм индексации не работает из-за несовместимости размерностей векторов.

### Step 8 — Логи scheduler ✅ PASS
Лог содержит записи о работе (сканирование, попытки индексации).

---

## 05_index_directory и 06_reindex_on_change

Все шаги с `POST /mcp` → ❌ FAIL (те же причины, что 02).
Шаги создания файлов → ✅ PASS.

---

## Обнаруженные дефекты — BUGFIX_CR

### CR-RAG-BUGFIX-001 — Вектор dimension mismatch: 5120 vs 1024
**Файл:** `JavaRagService/src/main/resources/application-local.yml`
**Проблема:** профиль local использует `qwen2.5-coder:14b` (5120 dim), индекс `rag-knowledge` создан для `multilingual-e5-large` (1024 dim).
**Фикс (выбрать одно):**
- A) Загрузить `multilingual-e5-large` в Ollama: `ollama pull multilingual-e5-large`, обновить `application-local.yml`
- B) Пересоздать индекс `rag-knowledge` с `dimension: 5120` (если решено использовать qwen2.5-coder для эмбеддингов)
**Приоритет:** CRITICAL — без фикса ни один документ не индексируется

### CR-RAG-BUGFIX-002 — Тест-сценарии используют несуществующий REST API
**Файлы:** все `test_e2e/*.md`
**Проблема:** сценарии предполагают `POST /mcp` (JSON RPC) и `POST /api/search` (REST),
но RagService реализует MCP через Spring AI SSE protocol, REST контроллеров нет.
**Фикс:** добавить REST-обёртку (`@RestController`) для `rag_index`, `rag_search`, `rag_status`, `rag_index_directory`.
**Или:** переписать сценарии для работы через SSE MCP протокол.
**Приоритет:** HIGH

### CR-RAG-BUGFIX-003 — env.sh использует localhost:9200 вместо 172.80.2.1:9200
**Файл:** `JavaRagService/test_e2e/env.sh`
**Проблема:** `OPENSEARCH_URL=http://localhost:9200` — snap Docker не форвардит через localhost.
**Фикс:** `OPENSEARCH_URL=http://172.80.2.1:9200`
**Приоритет:** MEDIUM

### CR-RAG-BUGFIX-004 — psql не установлен на хосте
**Файлы:** `01_health_check.md`, `02_index_single_document.md`, `04_scheduler_auto_index.md`, `05_index_directory.md`, `06_reindex_on_change.md`
**Проблема:** сценарии вызывают `psql` напрямую — команды падают.
**Фикс:** заменить на `docker exec leader-postgres psql -U rag_user -d leader_framework ...`
**Приоритет:** MEDIUM

### CR-RAG-BUGFIX-005 — Тест-документы не имеют required frontmatter
**Файлы:** все `test_e2e/*.md` — создаваемые тест-документы
**Проблема:** `FileIndexer` требует frontmatter с type `[SERVICE_CARD, PROCESS, GLOSSARY, ADR]`
и обязательными секциями. Тест-документы без frontmatter → `invalid`, не индексируются.
**Фикс:** обновить все создаваемые тест-документы — добавить валидный frontmatter.
Либо добавить тип `TEST` как допустимый тип документа в `DocumentValidator`.
**Приоритет:** HIGH

---

## Что реально работает

| Компонент | Статус |
|-----------|--------|
| Сервис стартует | ✅ |
| Spring AI MCP сервер (4 tools) | ✅ зарегистрированы |
| SSE endpoint `/sse` | ✅ |
| Scheduler обнаруживает файлы | ✅ |
| Валидатор документов | ✅ работает (но тест-файлы ему не соответствуют) |
| PostgreSQL схема `rag` | ✅ |
| OpenSearch индекс `rag-knowledge` | ✅ существует |
| Фактическая индексация | ❌ (dimension mismatch) |

---

## Приоритет исправлений

1. **CR-RAG-BUGFIX-001** — dimension mismatch (CRITICAL, блокирует всё)
2. **CR-RAG-BUGFIX-002** — нет REST API (HIGH, все тесты 02-06 неприменимы)
3. **CR-RAG-BUGFIX-005** — frontmatter в тест-документах (HIGH)
4. **CR-RAG-BUGFIX-003** — env.sh OpenSearch URL (MEDIUM)
5. **CR-RAG-BUGFIX-004** — psql не установлен (MEDIUM)
