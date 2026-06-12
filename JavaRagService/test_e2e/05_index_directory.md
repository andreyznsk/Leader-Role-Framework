# Scenario: /api/rag/index-directory — индексация папки целиком

**service:** JavaRagService
**port:** 8081
**priority:** MEDIUM
**depends_on:** postgres, opensearch, ollama

## Описание
Создать несколько .md файлов с корректным frontmatter в поддиректории →
вызвать /api/rag/index-directory → проверить что все файлы проиндексированы за один вызов.
Проверить что уже проиндексированные файлы пропускаются (idempotent).
Проверить pattern фильтр (только *.md).

## Переменные окружения
```bash
source JavaRagService/test_e2e/env.sh
```

## Steps

### Step 1 — Создать тестовую директорию с несколькими файлами
```bash
mkdir -p rag-inbox/e2e-batch-test

cat > rag-inbox/e2e-batch-test/adr-001.md <<'EOF'
---
type: ADR
title: Выбор PostgreSQL как основного хранилища
status: active
updated: 2026-06-12
---
# ADR-001: Выбор PostgreSQL как основного хранилища

## Статус
Accepted

## Контекст
Нам нужна реляционная БД для хранения задач, инцидентов и рисков.

## Решение
Выбираем PostgreSQL 16 за надёжность и поддержку JSONB.

## Последствия
Все сервисы используют единое хранилище через JPA/Hibernate.
EOF

cat > rag-inbox/e2e-batch-test/adr-002.md <<'EOF'
---
type: ADR
title: Выбор OpenSearch для векторного поиска
status: active
updated: 2026-06-12
---
# ADR-002: Выбор OpenSearch для векторного поиска

## Статус
Accepted

## Контекст
Нужно хранилище для векторных эмбеддингов документов.

## Решение
OpenSearch с kNN plugin поддерживает HNSW индекс и семантический поиск.

## Последствия
Размерность вектора зафиксирована в маппинге индекса.
EOF

cat > rag-inbox/e2e-batch-test/runbook.md <<'EOF'
---
type: ADR
title: Runbook P1 инцидент
status: active
updated: 2026-06-12
---
# ADR-RUNBOOK: Действия при P1 инциденте

## Статус
Active

## Контекст
P1 инциденты требуют немедленного реагирования в рабочее и нерабочее время.

## Решение
1. Уведомить команду в Slack канале #incidents
2. Создать инцидент в JavaMemoryService через MCP
3. Проверить дашборды Grafana
4. Выполнить rollback если деградация > 30%

## Последствия
Постмортем проводится в течение 24 часов после инцидента.
EOF

# Создать НЕ-md файл — он не должен быть проиндексирован
echo "this is not markdown" > rag-inbox/e2e-batch-test/ignore.txt

echo "Created 3 .md files + 1 .txt file in rag-inbox/e2e-batch-test/"
ls -la rag-inbox/e2e-batch-test/
```
**Expected:** 4 файла созданы (3 .md + 1 .txt)

### Step 2 — Вызвать /api/rag/index-directory
```bash
RESPONSE=$(curl -s --max-time 60 -X POST http://localhost:8081/api/rag/index-directory \
  -H "Content-Type: application/json" \
  -d '{"dir_path":"rag-inbox/e2e-batch-test","pattern":"*.md"}')
echo "$RESPONSE" | jq '.'
```
**Expected:** HTTP 200, поля: `indexed` >= 3, `skipped` = 0, `invalid` = 0 (первый раз)

### Step 3 — Все 3 .md файла появились в indexed_documents
```bash
docker exec leader-postgres psql -U rag_user -d leader_framework \
  -c "SELECT file_path, chunk_count, status FROM rag.indexed_documents WHERE file_path LIKE '%e2e-batch-test%' ORDER BY file_path;"
```
**Expected:** 3 строки, все со `status=indexed`

### Step 4 — .txt файл НЕ проиндексирован
```bash
docker exec leader-postgres psql -U rag_user -d leader_framework -t \
  -c "SELECT COUNT(*) FROM rag.indexed_documents WHERE file_path LIKE '%ignore.txt%';" \
  | tr -d ' '
```
**Expected:** `0`

### Step 5 — Повторный вызов /api/rag/index-directory — все файлы пропускаются
```bash
RESPONSE=$(curl -s --max-time 60 -X POST http://localhost:8081/api/rag/index-directory \
  -H "Content-Type: application/json" \
  -d '{"dir_path":"rag-inbox/e2e-batch-test","pattern":"*.md"}')
echo "$RESPONSE" | jq '.'
```
**Expected:** `indexed` = 0, `skipped` = 3 (все файлы уже проиндексированы с тем же hash)

### Step 6 — Поиск находит документы из батч-индексации
```bash
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
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
