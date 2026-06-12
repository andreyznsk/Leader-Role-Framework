# Scenario: rag_index_directory — индексация папки целиком

**service:** JavaRagService
**port:** 8081
**priority:** MEDIUM
**depends_on:** postgres, opensearch, ollama

## Описание
Создать несколько .md файлов в поддиректории → вызвать rag_index_directory →
проверить что все файлы проиндексированы за один вызов.
Проверить что уже проиндексированные файлы пропускаются (idempotent).
Проверить pattern фильтр (только *.md).

## Переменные окружения
```bash
export PGPASSWORD="${PGPASSWORD:-rag_password}"
export PGHOST="${PGHOST:-localhost}"
export PGUSER="${PGUSER:-rag_user}"
export PGDATABASE="${PGDATABASE:-leader_framework}"
```

## Steps

### Step 1 — Создать тестовую директорию с несколькими файлами
```bash
mkdir -p rag-inbox/e2e-batch-test

cat > rag-inbox/e2e-batch-test/adr-001.md <<'EOF'
# ADR-001: Выбор PostgreSQL как основного хранилища

## Статус: Accepted

## Контекст
Нам нужна реляционная БД для хранения задач, инцидентов и рисков.

## Решение
Выбираем PostgreSQL 16 за надёжность и поддержку JSONB.
EOF

cat > rag-inbox/e2e-batch-test/adr-002.md <<'EOF'
# ADR-002: Выбор OpenSearch для векторного поиска

## Статус: Accepted

## Контекст
Нужно хранилище для векторных эмбеддингов документов.

## Решение
OpenSearch с kNN plugin поддерживает HNSW индекс и семантический поиск.
EOF

cat > rag-inbox/e2e-batch-test/runbook.md <<'EOF'
# Runbook: Действия при P1 инциденте

1. Уведомить команду в Slack канале #incidents
2. Создать инцидент в JavaMemoryService через MCP
3. Проверить дашборды Grafana
4. Выполнить rollback если деградация > 30%
EOF

# Создать НЕ-md файл — он не должен быть проиндексирован
echo "this is not markdown" > rag-inbox/e2e-batch-test/ignore.txt

echo "Created 3 .md files + 1 .txt file in rag-inbox/e2e-batch-test/"
ls -la rag-inbox/e2e-batch-test/
```
**Expected:** 4 файла созданы (3 .md + 1 .txt)

### Step 2 — Вызвать rag_index_directory
```bash
RESPONSE=$(curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"rag_index_directory","params":{"dir_path":"rag-inbox/e2e-batch-test","pattern":"*.md"}}')
echo "$RESPONSE" | jq '.'
```
**Expected:** HTTP 200, поля: `files_indexed` >= 3, `files_skipped` = 0 (первый раз)

### Step 3 — Все 3 .md файла появились в indexed_documents
```bash
PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE \
  -c "SELECT file_path, chunk_count, status FROM rag.indexed_documents WHERE file_path LIKE '%e2e-batch-test%' ORDER BY file_path;"
```
**Expected:** 3 строки, все со `status=indexed`

### Step 4 — .txt файл НЕ проиндексирован
```bash
PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
  -c "SELECT COUNT(*) FROM rag.indexed_documents WHERE file_path LIKE '%ignore.txt%';" \
  | tr -d ' '
```
**Expected:** `0`

### Step 5 — Повторный вызов rag_index_directory — все файлы пропускаются
```bash
RESPONSE=$(curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"rag_index_directory","params":{"dir_path":"rag-inbox/e2e-batch-test","pattern":"*.md"}}')
echo "$RESPONSE" | jq '.'
```
**Expected:** `files_indexed` = 0, `files_skipped` = 3 (все файлы уже проиндексированы с тем же hash)

### Step 6 — Поиск находит документы из батч-индексации
```bash
curl -s -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"ADR архитектурное решение PostgreSQL","top_k":3}' \
  | jq '[.[] | select(.source | contains("e2e-batch-test")) | {source, text: .text[:80]}]'
```
**Expected:** хотя бы один результат из `e2e-batch-test/adr-001.md`

## Cleanup
```bash
rm -rf rag-inbox/e2e-batch-test/
echo "Cleanup: batch test directory removed"
```
