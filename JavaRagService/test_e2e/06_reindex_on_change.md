# Scenario: Переиндексация при изменении файла

**service:** JavaRagService
**port:** 8081
**priority:** MEDIUM
**depends_on:** postgres, opensearch, ollama

## Описание
Проверить полный цикл обновления документа:
индексировать → найти через поиск старое содержимое →
изменить файл (новый уникальный текст) → переиндексировать →
убедиться что старые чанки удалены и новые добавлены →
поиск находит новое содержимое, не находит старое.

## Переменные окружения
```bash
source JavaRagService/test_e2e/env.sh
```

## Steps

### Step 1 — Создать и проиндексировать версию V1
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-reindex-test.md <<'EOF'
---
type: ADR
title: Reindex Test V1
status: active
updated: 2026-06-12
---
# ADR-REINDEX: Документ версии V1

## Статус
Active

## Контекст
Содержимое первой версии документа.
Уникальная фраза для поиска: ZEBRA-UNIQUE-V1-PHRASE.
Этот текст должен быть найден до переиндексации.

## Решение
Первоначальное решение V1.

## Последствия
Будет заменено версией V2 в ходе теста.
EOF

curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/e2e-reindex-test.md"}' \
  | jq '{chunksAdded, status}'
```
**Expected:** `chunksAdded` > 0, `status` = `indexed`

### Step 2 — Поиск находит V1 контент
```bash
sleep 2
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"ZEBRA-UNIQUE-V1-PHRASE первая версия","top_k":3}' \
  | jq '[.[] | select(.source | contains("e2e-reindex-test")) | .text[:100]]'
```
**Expected:** хотя бы один результат с `ZEBRA-UNIQUE-V1-PHRASE`

### Step 3 — Запомнить hash V1
```bash
HASH_V1=$(docker exec leader-postgres psql -U rag_user -d leader_framework -t \
  -c "SELECT file_hash FROM rag.indexed_documents WHERE file_path LIKE '%e2e-reindex-test%';" \
  | tr -d ' \n')
CHUNKS_V1=$(docker exec leader-postgres psql -U rag_user -d leader_framework -t \
  -c "SELECT chunk_count FROM rag.indexed_documents WHERE file_path LIKE '%e2e-reindex-test%';" \
  | tr -d ' \n')
echo "V1 hash: $HASH_V1 | chunks: $CHUNKS_V1"
```
**Extract:** `$HASH_V1`, `$CHUNKS_V1`

### Step 4 — Заменить содержимое файла на V2 (новый уникальный текст)
```bash
cat > rag-inbox/e2e-reindex-test.md <<'EOF'
---
type: ADR
title: Reindex Test V2
status: active
updated: 2026-06-12
---
# ADR-REINDEX: Документ версии V2

## Статус
Active

## Контекст
Содержимое второй версии документа — полностью заменено.
Уникальная фраза для поиска: ELEPHANT-UNIQUE-V2-PHRASE.
Первой версии больше нет — старые чанки должны быть удалены.

## Решение
Обновлённое решение V2 с новыми данными.

## Последствия
Только V2 чанки должны присутствовать в OpenSearch после переиндексации.
EOF
echo "File updated to V2"
```
**Expected:** файл обновлён

### Step 5 — Переиндексировать через /api/rag/index
```bash
RESPONSE=$(curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/e2e-reindex-test.md"}')
echo "$RESPONSE" | jq '{chunksAdded, status}'
```
**Expected:** `chunksAdded` > 0 (не 0 — файл изменился), `status` = `indexed`

### Step 6 — Hash обновился в PostgreSQL
```bash
HASH_V2=$(docker exec leader-postgres psql -U rag_user -d leader_framework -t \
  -c "SELECT file_hash FROM rag.indexed_documents WHERE file_path LIKE '%e2e-reindex-test%';" \
  | tr -d ' \n')
echo "V1 hash: $HASH_V1"
echo "V2 hash: $HASH_V2"
[ "$HASH_V1" != "$HASH_V2" ] && echo "✅ Hash changed" || echo "❌ Hash same"
```
**Expected:** хэши различаются

### Step 7 — Поиск находит V2 контент
```bash
sleep 2
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"ELEPHANT-UNIQUE-V2-PHRASE вторая версия","top_k":3}' \
  | jq '[.[] | select(.source | contains("e2e-reindex-test")) | .text[:120]]'
```
**Expected:** хотя бы один результат с `ELEPHANT-UNIQUE-V2-PHRASE`

### Step 8 — Старый V1 контент больше не находится
```bash
RESULTS=$(curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"ZEBRA-UNIQUE-V1-PHRASE","top_k":3}')
V1_COUNT=$(echo "$RESULTS" | jq '[.[] | select(.source | contains("e2e-reindex-test")) | select(.text | contains("ZEBRA-UNIQUE-V1-PHRASE"))] | length')
echo "V1 chunks still in index: $V1_COUNT"
```
**Expected:** `0` — старые чанки удалены при переиндексации, ZEBRA-фраза не содержится ни в одном оставшемся чанке из этого документа

## Cleanup
```bash
rm -f rag-inbox/e2e-reindex-test.md
echo "Cleanup: reindex test file removed"
```
