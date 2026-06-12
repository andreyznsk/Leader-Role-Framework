# Scenario: Индексация одного документа через MCP rag_index

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** postgres, opensearch, ollama

## Описание
Создать тестовый .md файл в rag-inbox/ → вызвать rag_index через MCP →
проверить что чанки появились в OpenSearch → запись в indexed_documents PostgreSQL →
повторный вызов rag_index того же файла идемпотентен (hash совпадает → skip).

## Переменные окружения
```bash
export OPENSEARCH_URL="${OPENSEARCH_URL:-http://localhost:9200}"
export PGPASSWORD="${PGPASSWORD:-rag_password}"
export PGHOST="${PGHOST:-localhost}"
export PGUSER="${PGUSER:-rag_user}"
export PGDATABASE="${PGDATABASE:-leader_framework}"
```

## Steps

### Step 1 — Создать тестовый документ в rag-inbox/
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-test-doc.md <<'EOF'
# E2E Test Document

Этот документ создан автоматически для E2E тестирования RAG-сервиса.

## Раздел первый

Содержит ключевые слова: тестирование, индексация, семантический поиск.
Сервис JavaRagService индексирует Markdown-документы и сохраняет векторы в OpenSearch.

## Раздел второй

Ollama использует модель multilingual-e5-large для генерации эмбеддингов.
Размерность вектора: 1024. Поиск выполняется через kNN-запрос к OpenSearch.
EOF
echo "Test document created: rag-inbox/e2e-test-doc.md"
wc -l rag-inbox/e2e-test-doc.md
```
**Expected:** файл создан, > 5 строк

### Step 2 — Вызвать rag_index через MCP
```bash
RESPONSE=$(curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"rag_index","params":{"file_path":"rag-inbox/e2e-test-doc.md"}}')
echo "$RESPONSE" | jq '.'
```
**Expected:** HTTP 200, тело содержит `chunks_added` > 0 и `status` = `indexed`

### Step 3 — Запись появилась в indexed_documents
```bash
PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE \
  -c "SELECT file_path, chunk_count, status, indexed_at FROM rag.indexed_documents WHERE file_path LIKE '%e2e-test-doc%';"
```
**Expected:** строка с `file_path` содержащим `e2e-test-doc`, `status=indexed`, `chunk_count` > 0

### Step 4 — Чанки появились в OpenSearch
```bash
curl -s "$OPENSEARCH_URL/rag-knowledge/_search" \
  -H "Content-Type: application/json" \
  -d '{"query":{"match":{"source":"rag-inbox/e2e-test-doc.md"}},"_source":["text","source","chunk_index"]}' \
  | jq '{total: .hits.total.value, chunks: [.hits.hits[]._source | {chunk_index, text: .text[:80]}]}'
```
**Expected:** `total` >= 1, каждый chunk содержит поле `source` = `rag-inbox/e2e-test-doc.md`

### Step 5 — Повторный rag_index того же файла — идемпотентен (hash совпадает)
```bash
RESPONSE=$(curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"rag_index","params":{"file_path":"rag-inbox/e2e-test-doc.md"}}')
echo "$RESPONSE" | jq '.'
```
**Expected:** HTTP 200, `chunks_added` = 0 или `status` = `skipped` / `already_indexed`
(файл не изменился — повторная индексация не нужна)

### Step 6 — rag_status показывает документ
```bash
curl -s http://localhost:8081/mcp/rag_status | jq '[.[] | select(.file_path | contains("e2e-test-doc"))]'
```
**Expected:** массив содержит запись для `e2e-test-doc.md` со статусом `indexed`

## Cleanup
```bash
# Удалить тестовый файл
rm -f rag-inbox/e2e-test-doc.md
echo "Cleanup: test document removed from rag-inbox"
# Чанки в OpenSearch и запись в PostgreSQL оставляем — они не мешают
# (при следующем запуске scheduler обнаружит что файл удалён — статус станет outdated)
```
