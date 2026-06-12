# Scenario: Capture Bot — все 7 типов маршрутизируются корректно

**service:** JavaMemoryService + JavaRagService
**ports:** 8082, 8081
**priority:** HIGH
**depends_on:** postgres, opensearch, ollama
**profile:** local,e2e (mock.capture-agent=true)

## Описание
Батч из 7 capture-заметок, каждая с явным маркером типа.
Проверяем маршрутизацию всех 7 типов включая KNOWLEDGE → RagService.

Mock-классификатор распознаёт маркеры: `TASK:`, `RISK:`, `NOTE:`,
`QUESTION:`, `PERSON_NOTE:`, `KNOWLEDGE:`, `JOURNAL:`.

## Preconditions
- JavaMemoryService запущен с профилем `local,e2e` (`mock.capture-agent=true`)
- JavaRagService запущен на :8081
- Директории `JavaRagService/rag-inbox/captures` и `workspace/08_daily_journal` существуют

## Steps

### Step 1 — Подготовить окружение
```bash
RUN_ID="it07-$(date +%s)"
TODAY=$(date +%Y-%m-%d)
mkdir -p JavaRagService/rag-inbox/captures workspace/08_daily_journal
echo "RUN_ID=$RUN_ID"
```
**Extract:** `$RUN_ID`, `$TODAY`

### Step 2 — Создать 7 capture-заметок
```bash
create_capture() {
  curl -s -X POST $MS_URL/api/capture \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"$1\",\"source\":\"manual\"}" | jq -r '.file'
}

TASK_FILE=$(create_capture   "TASK: $RUN_ID Нужно исправить баг в payments сервисе")
RISK_FILE=$(create_capture   "RISK: $RUN_ID Только один человек знает deployment process")
NOTE_FILE=$(create_capture   "NOTE: $RUN_ID Команда договорилась не трогать монолит до Q3")
QUESTION_FILE=$(create_capture "QUESTION: $RUN_ID Как правильно настроить retry в Kafka?")
PERSON_FILE=$(create_capture  "PERSON_NOTE: IT07Person $RUN_ID | Хочет перейти в архитектурную роль")
KNOWLEDGE_FILE=$(create_capture "KNOWLEDGE: $RUN_ID | Runbook: сборка mvn package, деплой helm upgrade")
JOURNAL_FILE=$(create_capture "JOURNAL: $RUN_ID | Сегодня завершили миграцию схемы БД")

printf "Created:\n%s\n%s\n%s\n%s\n%s\n%s\n%s\n" \
  "$TASK_FILE" "$RISK_FILE" "$NOTE_FILE" "$QUESTION_FILE" "$PERSON_FILE" "$KNOWLEDGE_FILE" "$JOURNAL_FILE"
```
**Expected:** 7 путей `capture-inbox/YYYY-MM-DD/*.md`
**Extract:** все `$*_FILE` переменные

### Step 3 — Запустить обработку
```bash
curl -s -X POST $MS_URL/api/capture/process-now | jq '{total, routed}'
```
**Expected:** `total=7`, `routed=7`

### Step 4 — Дождаться перемещения файлов в processed/ (до 60 сек)
```bash
PROCESSED_DIR="capture-inbox/processed/$TODAY"
all_processed() {
  for f in "$TASK_FILE" "$RISK_FILE" "$NOTE_FILE" "$QUESTION_FILE" "$PERSON_FILE" "$KNOWLEDGE_FILE" "$JOURNAL_FILE"; do
    test -f "$PROCESSED_DIR/$(basename "$f")" || return 1
  done
  return 0
}
for i in $(seq 1 60); do
  all_processed && echo "✅ All 7 files in processed/" && break
  sleep 1
done
all_processed && echo "files OK" || echo "❌ Not all files moved"
```
**Expected:** все 7 файлов в `capture-inbox/processed/YYYY-MM-DD/`

### Step 5 — TASK → PENDING очередь
```bash
TASK_ID=$(curl -s $MS_URL/api/tasks/pending \
  | jq -r --arg r "$RUN_ID" '[.[] | select(.title | contains($r))] | first | .id')
[ -n "$TASK_ID" ] && [ "$TASK_ID" != "null" ] && echo "✅ TASK route OK (id=$TASK_ID)" || echo "❌ TASK route FAIL"
```
**Expected:** TASK route OK
**Extract:** `$TASK_ID`

### Step 6 — RISK → открытые риски
```bash
RISK_ID=$(curl -s "$MS_URL/api/risks?status=OPEN" \
  | jq -r --arg r "$RUN_ID" '[.[] | select(.title | contains($r))] | first | .id')
[ -n "$RISK_ID" ] && [ "$RISK_ID" != "null" ] && echo "✅ RISK route OK (id=$RISK_ID)" || echo "❌ RISK route FAIL"
```
**Expected:** RISK route OK
**Extract:** `$RISK_ID`

### Step 7 — NOTE → /api/notes
```bash
NOTE_ID=$(curl -s "$MS_URL/api/notes?limit=50" \
  | jq -r --arg r "$RUN_ID" '[.[] | select(.text | contains($r))] | first | .id')
[ -n "$NOTE_ID" ] && [ "$NOTE_ID" != "null" ] && echo "✅ NOTE route OK" || echo "❌ NOTE route FAIL"
```
**Expected:** NOTE route OK

### Step 8 — QUESTION → /api/questions
```bash
QUESTION_ID=$(curl -s "$MS_URL/api/questions?status=OPEN" \
  | jq -r --arg r "$RUN_ID" '[.[] | select(.title | contains($r))] | first | .id')
[ -n "$QUESTION_ID" ] && [ "$QUESTION_ID" != "null" ] && echo "✅ QUESTION route OK" || echo "❌ QUESTION route FAIL"
```
**Expected:** QUESTION route OK

### Step 9 — PERSON_NOTE → заметки по имени
```bash
ENCODED=$(python3 -c "import sys,urllib.parse; print(urllib.parse.quote(sys.argv[1]))" "IT07Person $RUN_ID")
COUNT=$(curl -s "$MS_URL/api/people/name/$ENCODED/notes" \
  | jq --arg r "$RUN_ID" '[.[] | select(.note | contains($r))] | length')
[ "$COUNT" = "1" ] && echo "✅ PERSON_NOTE route OK" || echo "❌ PERSON_NOTE route FAIL (count=$COUNT)"
```
**Expected:** PERSON_NOTE route OK

### Step 10 — KNOWLEDGE → rag-inbox/captures
```bash
grep -R "$RUN_ID" JavaRagService/rag-inbox/captures/ > /dev/null \
  && echo "✅ KNOWLEDGE route OK" || echo "❌ KNOWLEDGE route FAIL"
```
**Expected:** KNOWLEDGE route OK

### Step 11 — JOURNAL → daily journal файл
```bash
grep -R "$RUN_ID" workspace/08_daily_journal/ > /dev/null \
  && echo "✅ JOURNAL route OK" || echo "❌ JOURNAL route FAIL"
```
**Expected:** JOURNAL route OK

### Step 12 — KNOWLEDGE проиндексировано в RagService (scheduler до 90 сек)
```bash
DOCS_BEFORE=$(curl -s $RAG_URL/api/rag/status | jq 'length')
for i in $(seq 1 18); do
  sleep 5
  DOCS_AFTER=$(curl -s $RAG_URL/api/rag/status | jq 'length')
  [ "$DOCS_AFTER" -gt "$DOCS_BEFORE" ] && echo "✅ RAG indexed new doc" && break
  echo "  Attempt $i/18: docs $DOCS_BEFORE → $DOCS_AFTER"
done
```
**Expected:** `$DOCS_AFTER > $DOCS_BEFORE`

## Cleanup
```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/delete" > /dev/null 2>&1 || true
curl -s -X DELETE "$MS_URL/api/risks/$RISK_ID" > /dev/null 2>&1 || true
find JavaRagService/rag-inbox/captures -name "*.md" -exec grep -l "$RUN_ID" {} \; | xargs -r rm -f
find workspace/08_daily_journal -name "*.md" -exec grep -l "$RUN_ID" {} \; | xargs -r sed -i "/$RUN_ID/d"
echo "IT-07 cleanup done"
```
