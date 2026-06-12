# Scenario: Index Document and Search

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** opensearch, postgres, ollama

## Preconditions
- JavaRagService запущен на :8081
- OpenSearch доступен на $OPENSEARCH_URL
- Ollama запущен на :11434 с моделью multilingual-e5-large

## Steps

### Step 1 — Создать тестовый документ в rag-inbox
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-test-doc.md <<'EOF'
---
type: ADR
title: E2E Test Document
status: active
updated: 2026-06-12
---
# ADR-E2E: E2E Test Document

## Статус
Active

## Контекст
Этот документ создан автоматически для E2E тестирования RAG-сервиса.
Содержит ключевые слова: тестирование, индексация, семантический поиск.

## Решение
Использовать ADR формат как стандартный тип документа для тест-сценариев.

## Последствия
Документ должен быть проиндексирован и найден через семантический поиск.
EOF
```
**Expected:** файл создан

### Step 2 — Индексировать через REST API
```bash
curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/e2e-test-doc.md"}'
```
**Expected:** HTTP 200, тело содержит `chunksAdded` > 0, `status` = `indexed`

### Step 3 — Семантический поиск
```bash
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"тестирование RAG индексация","top_k":3}'
```
**Expected:** HTTP 200, результаты содержат `source` = `rag-inbox/e2e-test-doc.md`

## Cleanup
```bash
rm -f rag-inbox/e2e-test-doc.md
```
