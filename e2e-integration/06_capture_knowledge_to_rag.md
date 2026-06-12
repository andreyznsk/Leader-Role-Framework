# Scenario: Capture KNOWLEDGE → rag-inbox → автоиндексация RagService

**service:** JavaMemoryService + JavaRagService
**ports:** 8082, 8081
**priority:** HIGH
**depends_on:** postgres, opensearch, ollama
**profile:** local,e2e (mock.capture-agent=true)

## Описание
Capture с маркером KNOWLEDGE маршрутизируется в `rag-inbox/captures/` и
автоматически подхватывается scheduler JavaRagService.
Итог — знание находится через семантический поиск.

## Preconditions
- JavaMemoryService запущен с профилем `local,e2e` (`mock.capture-agent=true`)
- JavaRagService запущен на :8081
- Ollama запущена локально (`ollama list | grep mxbai-embed-large`)
- OpenSearch доступен на $OPENSEARCH_URL

## Steps

### Step 1 — Оба сервиса живы
```bash
MS=$(curl -s -o /dev/null -w "%{http_code}" $MS_URL/actuator/health)
RAG=$(curl -s -o /dev/null -w "%{http_code}" $RAG_URL/actuator/health)
echo "MemoryService: $MS | RagService: $RAG"
```
**Expected:** оба 200

### Step 2 — Запомнить количество документов до теста
```bash
DOCS_BEFORE=$(curl -s $RAG_URL/api/rag/status | jq 'length')
echo "RAG docs before: $DOCS_BEFORE"
mkdir -p JavaRagService/rag-inbox/captures
```
**Extract:** `$DOCS_BEFORE`

### Step 3 — Отправить KNOWLEDGE capture (mock маршрутизирует по маркеру)
```bash
RUN_ID="it06-$(date +%s)"
KNOWLEDGE_TEXT="KNOWLEDGE: $RUN_ID | Release pipeline: сборка в Jenkins, деплой через Helm, smoke тесты обязательны"
CAPTURE_RESPONSE=$(curl -s -X POST $MS_URL/api/capture \
  -H "Content-Type: application/json" \
  -d "{\"text\":\"$KNOWLEDGE_TEXT\",\"source\":\"manual\"}")
CAPTURE_FILE=$(echo "$CAPTURE_RESPONSE" | jq -r '.file')
echo "Capture saved to: $CAPTURE_FILE | RUN_ID=$RUN_ID"
```
**Expected:** `"saved":true`, `file` непустой
**Extract:** `$RUN_ID`, `$CAPTURE_FILE`

### Step 4 — Запустить обработку capture вручную
```bash
curl -s -X POST $MS_URL/api/capture/process-now | jq '{total, routed}'
```
**Expected:** HTTP 200, `routed >= 1`

### Step 5 — Файл появился в rag-inbox/captures/
```bash
for i in $(seq 1 30); do
  FOUND=$(grep -R "$RUN_ID" JavaRagService/rag-inbox/captures/ 2>/dev/null | wc -l)
  [ "$FOUND" -ge 1 ] && echo "✅ Knowledge file in rag-inbox/captures (attempt $i)" && break
  sleep 2
done
KNOWLEDGE_FILE=$(grep -R "$RUN_ID" JavaRagService/rag-inbox/captures/ -l 2>/dev/null | head -1)
echo "File: $KNOWLEDGE_FILE"
```
**Expected:** файл найден
**Extract:** `$KNOWLEDGE_FILE`

### Step 6 — Дождаться автоиндексации RagService scheduler (до 90 сек)
```bash
for i in $(seq 1 18); do
  sleep 5
  DOCS_AFTER=$(curl -s $RAG_URL/api/rag/status | jq 'length')
  echo "  Attempt $i/18: docs $DOCS_BEFORE → $DOCS_AFTER"
  [ "$DOCS_AFTER" -gt "$DOCS_BEFORE" ] && echo "  ✅ New doc indexed" && break
done
```
**Expected:** `$DOCS_AFTER > $DOCS_BEFORE`

### Step 7 — Семантический поиск находит проиндексированное знание
```bash
SEARCH=$(curl -s -X POST $RAG_URL/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"release pipeline Jenkins Helm smoke","top_k":3}')
echo "$SEARCH" | jq '.[0] | {source, score}'
FOUND_COUNT=$(echo "$SEARCH" | jq "[.[] | select(.text | contains(\"$RUN_ID\"))] | length")
echo "Found in search: $FOUND_COUNT"
```
**Expected:** `$FOUND_COUNT >= 1`, `score > 0.3`

## Cleanup
```bash
rm -f "$KNOWLEDGE_FILE" 2>/dev/null || true
echo "IT-06 cleanup done (RAG doc remains indexed)"
```
