# Scenario: Index Document and Search

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** opensearch, postgres, ollama

## Preconditions
- JavaRagService запущен на :8081
- OpenSearch доступен на :9200
- Ollama запущен на :11434 с моделью multilingual-e5-large

## Steps

### Step 1 — Создать тестовый документ в rag-inbox
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-test-doc.md <<'EOF'
# E2E Test Document

Этот документ создан автоматически для E2E тестирования RAG-сервиса.
Содержит ключевые слова: тестирование, индексация, семантический поиск.
EOF
```
**Expected:** файл создан

### Step 2 — Индексировать через MCP tool
```bash
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"rag_index","params":{"file_path":"rag-inbox/e2e-test-doc.md"}}'
```
**Expected:** HTTP 200, тело содержит `chunks_added` > 0

### Step 3 — Семантический поиск
```bash
curl -s -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"тестирование RAG индексация","top_k":3}'
```
**Expected:** HTTP 200, результаты содержат source `rag-inbox/e2e-test-doc.md`

## Cleanup
```bash
rm -f rag-inbox/e2e-test-doc.md
```
