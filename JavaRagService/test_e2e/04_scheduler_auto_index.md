# Scenario: Scheduler — автоматическая индексация из rag-inbox/

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** postgres, opensearch, ollama

## Описание
Положить файл в rag-inbox/ вручную (без вызова rag_index) →
дождаться scheduler (≤60 сек) → проверить что файл автоматически проиндексирован.
Проверить идемпотентность: изменить файл → scheduler переиндексирует (новый hash).
Проверить skip: не изменённый файл пропускается.

## Переменные окружения
```bash
export PGPASSWORD="${PGPASSWORD:-rag_password}"
export PGHOST="${PGHOST:-localhost}"
export PGUSER="${PGUSER:-rag_user}"
export PGDATABASE="${PGDATABASE:-leader_framework}"
```

## Steps

### Step 1 — Убедиться что файла ещё нет в indexed_documents
```bash
PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
  -c "SELECT COUNT(*) FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%';" \
  | tr -d ' '
```
**Expected:** `0`

### Step 2 — Положить новый файл в rag-inbox/ (без вызова rag_index)
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-scheduler-test.md <<'EOF'
# Scheduler Auto-Index Test

Этот документ должен быть автоматически проиндексирован scheduler-ом.
Ключевые слова для поиска: scheduler, автоиндексация, rag-inbox.

## Архитектурное решение ADR-007

Сервис использует scheduleWithFixedDelay для периодического сканирования папки.
Интервал: 60 секунд. Один поток исключает конкурентную индексацию.
EOF
echo "File created at: $(date)"
ls -la rag-inbox/e2e-scheduler-test.md
```
**Expected:** файл создан

### Step 3 — Дождаться автоматической индексации (до 90 сек)
```bash
echo "Waiting for scheduler to pick up the file..."
for i in $(seq 1 18); do
  sleep 5
  COUNT=$(PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
    -c "SELECT COUNT(*) FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%' AND status='indexed';" \
    2>/dev/null | tr -d ' ')
  echo "  Attempt $i/18: indexed=$COUNT"
  if [ "$COUNT" -ge 1 ] 2>/dev/null; then
    echo "  ✅ File auto-indexed by scheduler"
    break
  fi
done
```
**Expected:** count >= 1 в течение 90 секунд

### Step 4 — Запись в indexed_documents корректна
```bash
PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE \
  -c "SELECT file_path, chunk_count, status, file_hash FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%';"
```
**Expected:** строка с `status=indexed`, `chunk_count` > 0, `file_hash` непустой
**Extract:** `file_hash` → `$HASH_BEFORE`

### Step 5 — Файл доступен через поиск
```bash
curl -s -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"автоиндексация scheduler rag-inbox","top_k":3}' \
  | jq '[.[] | select(.source | contains("e2e-scheduler"))] | length'
```
**Expected:** >= 1

### Step 6 — Изменить файл — scheduler переиндексирует (новый hash)
```bash
echo "" >> rag-inbox/e2e-scheduler-test.md
echo "## Обновление" >> rag-inbox/e2e-scheduler-test.md
echo "Файл изменён в $(date) для проверки переиндексации." >> rag-inbox/e2e-scheduler-test.md
echo "File modified at: $(date)"

HASH_BEFORE=$(PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
  -c "SELECT file_hash FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%';" \
  | tr -d ' \n')
echo "Hash before: $HASH_BEFORE"
```
**Expected:** файл изменён

### Step 7 — Дождаться переиндексации изменённого файла (до 90 сек)
```bash
echo "Waiting for re-indexing..."
for i in $(seq 1 18); do
  sleep 5
  HASH_AFTER=$(PGPASSWORD=$PGPASSWORD psql -h $PGHOST -U $PGUSER -d $PGDATABASE -t \
    -c "SELECT file_hash FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%';" \
    2>/dev/null | tr -d ' \n')
  echo "  Attempt $i/18: hash=$HASH_AFTER"
  if [ -n "$HASH_AFTER" ] && [ "$HASH_AFTER" != "$HASH_BEFORE" ]; then
    echo "  ✅ File re-indexed (new hash)"
    break
  fi
done
```
**Expected:** `HASH_AFTER` отличается от `HASH_BEFORE`

### Step 8 — Лог содержит записи о работе scheduler
```bash
grep -i "scheduler\|indexed\|rag-inbox\|scanning" logs/JavaRagService.log 2>/dev/null | tail -5
```
**Expected:** строки с упоминанием индексации файлов

## Cleanup
```bash
rm -f rag-inbox/e2e-scheduler-test.md
echo "Cleanup: scheduler test file removed"
```
