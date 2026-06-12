# Scenario: Индексация одного документа через /api/rag/index

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** postgres, opensearch, ollama

## Описание
Создать тестовый .md файл с корректным frontmatter в rag-inbox/ → вызвать /api/rag/index →
проверить что чанки появились в OpenSearch → запись в indexed_documents PostgreSQL →
повторный вызов /api/rag/index того же файла идемпотентен (hash совпадает → skip).

## Переменные окружения
```bash
source JavaRagService/test_e2e/env.sh
```

## Steps

### Step 1 — Создать тестовый документ в rag-inbox/
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
Сервис JavaRagService индексирует Markdown-документы и сохраняет векторы в OpenSearch.
Ollama использует модель multilingual-e5-large для генерации эмбеддингов.

## Решение
Сервис использует kNN-запрос к OpenSearch для семантического поиска.
Размерность вектора: 1024.

## Последствия
Документ должен быть проиндексирован и доступен через поиск.
EOF
echo "Test document created: rag-inbox/e2e-test-doc.md"
wc -l rag-inbox/e2e-test-doc.md
```
**Expected:** файл создан, > 5 строк

### Step 2 — Вызвать /api/rag/index
```bash
RESPONSE=$(curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/e2e-test-doc.md"}')
echo "$RESPONSE" | jq '.'
```
**Expected:** HTTP 200, тело содержит `chunksAdded` > 0 и `status` = `indexed`

### Step 3 — Запись появилась в indexed_documents
```bash
docker exec leader-postgres psql -U rag_user -d leader_framework \
  -c "SELECT file_path, chunk_count, status, indexed_at FROM rag.indexed_documents WHERE file_path LIKE '%e2e-test-doc%';"
```
**Expected:** строка с `file_path` содержащим `e2e-test-doc`, `status=indexed`, `chunk_count` > 0

### Step 4 — Чанки появились в OpenSearch
```bash
curl -s --max-time 10 "$OPENSEARCH_URL/rag-knowledge/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"match":{"source":"rag-inbox/e2e-test-doc.md"}},"_source":["text","source","chunk_index"]}' \
  | jq '{total: .hits.total.value, chunks: [.hits.hits[]._source | {chunk_index, text: .text[:80]}]}'
```
**Expected:** `total` >= 1, каждый chunk содержит поле `source` = `rag-inbox/e2e-test-doc.md`

### Step 5 — Повторный /api/rag/index того же файла — идемпотентен (hash совпадает)
```bash
RESPONSE=$(curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/e2e-test-doc.md"}')
echo "$RESPONSE" | jq '.'
```
**Expected:** HTTP 200, `chunksAdded` = 0 и `status` = `skipped`
(файл не изменился — повторная индексация не нужна)

### Step 6 — /api/rag/status показывает документ
```bash
curl -s --max-time 10 http://localhost:8081/api/rag/status \
  | jq '[.[] | select(.filePath | contains("e2e-test-doc"))]'
```
**Expected:** массив содержит запись для `e2e-test-doc.md` со статусом `indexed`

## Cleanup
```bash
rm -f rag-inbox/e2e-test-doc.md
echo "Cleanup: test document removed from rag-inbox"
```
