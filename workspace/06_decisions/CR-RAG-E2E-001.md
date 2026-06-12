---
type: ADR
title: CR-RAG-E2E-001 — Обновление E2E тест-сценариев JavaRagService
status: active
updated: 2026-06-12
---

# CR-RAG-E2E-001

## Статус
Выполнен — 2026-06-12

## Контекст
E2E тест-сценарии в `JavaRagService/test_e2e/` были написаны под более раннюю версию сервиса.
После добавления REST API (`RagRestController`) и валидации документов (`DocumentValidator`)
сценарии перестали соответствовать реальному поведению сервиса.

По результатам прогона 2026-06-12 выявлено три категории проблем:

**Проблема 1 — Неверные endpoints (все сценарии 02–05)**
Тесты использовали `POST /mcp` (endpoint не существует).
Реальный REST API: `/api/rag/index`, `/api/rag/index-directory`, `/api/rag/status`, `/api/search`.

**Проблема 2 — Тестовые документы без frontmatter (сценарии 02–05)**
`DocumentValidator` требует YAML frontmatter с полем `type` и обязательными секциями.
Все тестовые документы создавались без frontmatter → статус `invalid`, chunksAdded=0.

**Проблема 3 — Метрика `indexed` в DirectoryIndexResult вводит в заблуждение**
Метод `ragIndexDirectory` считал документы со статусом `invalid` как `indexed`.
Тест ожидал `files_indexed >= 3` и получал `3`, но реально 0 документов попало в OpenSearch.

**Проблема 4 — OpenSearch 3.x убрал поле `status` из корневого ответа**
`GET /` больше не содержит `status`. Нужен `GET /_cluster/health`.

**Проблема 5 — env.sh использует localhost:9200 вместо 172.80.2.1:9200**
Local profile сервиса настроен на `172.80.2.1:9200`.

## Решение

### Изменения в коде (RagMcpTools.java)
- `DirectoryIndexResult` добавлено поле `invalid`
- `ragIndexDirectory()`: документы со статусом `invalid` теперь попадают в счётчик `invalid`, а не `indexed`

### Изменения в тест-сценариях
- `env.sh`: исправлен `OPENSEARCH_URL`
- `01_health_check.md`: Step 2 → `/_cluster/health`; Step 6 → `GET /api/rag/status`
- `02_index_and_search.md`: endpoint → `/api/rag/index`, frontmatter в тест-документе
- `02_index_single_document.md`: endpoints, frontmatter, ожидаемые поля ответа (camelCase)
- `03_semantic_search.md`: endpoints, frontmatter
- `04_scheduler_auto_index.md`: frontmatter в тест-документе
- `05_index_directory.md`: endpoint → `/api/rag/index-directory`, frontmatter, поля ответа
- `06_reindex_on_change.md`: endpoints, frontmatter V1/V2, Step 8 (убран ZEBRA из V2-текста)

## Последствия
После CR все E2E сценарии должны выполняться успешно при запущенном сервисе
с корректной инфраструктурой (PostgreSQL, OpenSearch, Ollama).
