# Scenario: Scheduler — автоматическая индексация из rag-inbox/

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** postgres, opensearch, ollama

## Описание
Положить файл в rag-inbox/ вручную (без вызова /api/rag/index) →
дождаться scheduler (≤60 сек) → проверить что файл автоматически проиндексирован.
Проверить идемпотентность: изменить файл → scheduler переиндексирует (новый hash).
Проверить skip: не изменённый файл пропускается.

## Переменные окружения
```bash
source JavaRagService/test_e2e/env.sh
```

## Steps

### Step 1 — Убедиться что файла ещё нет в indexed_documents
```bash
docker exec leader-postgres psql -U rag_user -d leader_framework -t \
  -c "SELECT COUNT(*) FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%';" \
  | tr -d ' '
```
**Expected:** `0`

### Step 2 — Положить новый файл в rag-inbox/ (без вызова /api/rag/index)
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-scheduler-test.md <<'EOF'
---
type: ADR
title: Scheduler Auto-Index Test
status: active
updated: 2026-06-12
---
# ADR-SCHEDULER: Scheduler Auto-Index Test

## Статус
Active

## Контекст
Этот документ должен быть автоматически проиндексирован scheduler-ом.
Ключевые слова для поиска: scheduler, автоиндексация, rag-inbox.

## Решение
Сервис использует scheduleWithFixedDelay для периодического сканирования папки.
Интервал: 60 секунд. Один поток исключает конкурентную индексацию.

## Последствия
Файлы с корректным frontmatter автоматически попадают в индекс.
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
  COUNT=$(docker exec leader-postgres psql -U rag_user -d leader_framework -t \
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
docker exec leader-postgres psql -U rag_user -d leader_framework \
  -c "SELECT file_path, chunk_count, status, file_hash FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%';"
```
**Expected:** строка с `status=indexed`, `chunk_count` > 0, `file_hash` непустой
**Extract:** `file_hash` → `$HASH_BEFORE`

### Step 5 — Файл доступен через поиск
```bash
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"автоиндексация scheduler rag-inbox","top_k":3}' \
  | jq '[.[] | select(.source | contains("e2e-scheduler"))] | length'
```
**Expected:** >= 1

### Step 6 — Изменить файл — scheduler переиндексирует (новый hash)
```bash
HASH_BEFORE=$(docker exec leader-postgres psql -U rag_user -d leader_framework -t \
  -c "SELECT file_hash FROM rag.indexed_documents WHERE file_path LIKE '%e2e-scheduler%';" \
  | tr -d ' \n')
echo "Hash before: $HASH_BEFORE"

cat >> rag-inbox/e2e-scheduler-test.md <<'EOF'

## Обновление
Файл изменён для проверки переиндексации.
EOF
echo "File modified at: $(date)"
```
**Expected:** файл изменён

### Step 7 — Дождаться переиндексации изменённого файла (до 90 сек)
```bash
echo "Waiting for re-indexing..."
for i in $(seq 1 18); do
  sleep 5
  HASH_AFTER=$(docker exec leader-postgres psql -U rag_user -d leader_framework -t \
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
