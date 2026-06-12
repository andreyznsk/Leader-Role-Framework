# Scenario: Capture Classification — mock agent routes all types

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0 (mock.capture-agent)

## Описание
Проверить полный процесс классификации capture-файлов без реального Claude:
- сервис запущен с `mock.capture-agent=true`
- scheduler работает раз в минуту через `capture.scheduler.cron=0 */1 * * * *`
- batch из 7 заметок классифицируется в `TASK`, `RISK`, `NOTE`, `QUESTION`,
  `PERSON_NOTE`, `KNOWLEDGE`, `JOURNAL`
- каждый тип попадает в свой route
- все исходные файлы перемещаются в `capture-inbox/processed/YYYY-MM-DD/`

Mock-классификатор использует явные маркеры в начале текста:
`TASK:`, `RISK:`, `NOTE:`, `QUESTION:`, `PERSON_NOTE:`, `KNOWLEDGE:`, `JOURNAL:`.

## Preconditions
- JavaMemoryService запущен из корня репозитория на :8082
- Профили запуска: `SPRING_PROFILES_ACTIVE=local,e2e`
- PostgreSQL доступен на :5432
- `jq` установлен

Пример запуска:
```bash
SPRING_PROFILES_ACTIVE=local,e2e mvn -pl JavaMemoryService spring-boot:run
```

## Steps

### Step 1 — Проверить что сервис поднят
```bash
curl -s http://localhost:8082/actuator/health | jq -r '.status'
```
**Expected:** результат `UP`

### Step 2 — Подготовить уникальный marker и директории
```bash
RUN_ID="e2e-classify-$(date +%s)"
TODAY=$(date +%Y-%m-%d)
CAPTURE_DIR="capture-inbox/$TODAY"
PROCESSED_DIR="capture-inbox/processed/$TODAY"
mkdir -p "$CAPTURE_DIR" "$PROCESSED_DIR" JavaRagService/rag-inbox/captures workspace/08_daily_journal
echo "RUN_ID=$RUN_ID"
```
**Expected:** команда завершается без ошибки
**Extract:** marker → `$RUN_ID`

### Step 3 — Создать 7 capture-заметок
```bash
create_capture() {
  local text="$1"
  curl -s -X POST http://localhost:8082/api/capture \
    -H "Content-Type: application/json" \
    -d "{\"text\":\"$text\",\"source\":\"manual\"}" | jq -r '.file'
}

TASK_FILE=$(create_capture "TASK: $RUN_ID TASK title | $RUN_ID Сделать действие HIGH")
RISK_FILE=$(create_capture "RISK: $RUN_ID RISK title | $RUN_ID Операционный риск")
NOTE_FILE=$(create_capture "NOTE: $RUN_ID NOTE title | $RUN_ID Информация к сведению")
QUESTION_FILE=$(create_capture "QUESTION: $RUN_ID QUESTION title | $RUN_ID Что уточнить?")
PERSON_FILE=$(create_capture "PERSON_NOTE: $RUN_ID Person | $RUN_ID Хочет больше архитектурных задач")
KNOWLEDGE_FILE=$(create_capture "KNOWLEDGE: $RUN_ID KNOWLEDGE title | $RUN_ID Runbook knowledge")
JOURNAL_FILE=$(create_capture "JOURNAL: $RUN_ID JOURNAL title | $RUN_ID Итоги дня")

printf "%s\n" "$TASK_FILE" "$RISK_FILE" "$NOTE_FILE" "$QUESTION_FILE" "$PERSON_FILE" "$KNOWLEDGE_FILE" "$JOURNAL_FILE"
```
**Expected:** выведено 7 путей `capture-inbox/YYYY-MM-DD/*.md`
**Extract:** paths → `$TASK_FILE ... $JOURNAL_FILE`

### Step 4 — Дождаться scheduler и перемещения всех файлов в processed
```bash
all_processed() {
  for f in "$TASK_FILE" "$RISK_FILE" "$NOTE_FILE" "$QUESTION_FILE" "$PERSON_FILE" "$KNOWLEDGE_FILE" "$JOURNAL_FILE"; do
    base=$(basename "$f")
    test -f "$PROCESSED_DIR/$base" || return 1
  done
  return 0
}

for i in $(seq 1 100); do
  if all_processed; then
    echo "all processed"
    break
  fi
  sleep 1
done
all_processed && echo "processed files OK"
```
**Expected:** вывод `processed files OK`

### Step 5 — TASK попал в PENDING задачи
```bash
TASK_ID=$(curl -s http://localhost:8082/api/tasks/pending \
  | jq -r --arg title "$RUN_ID TASK title" '.[] | select(.title == $title) | .id' | head -1)
echo "TASK_ID=$TASK_ID"
test -n "$TASK_ID" && test "$TASK_ID" != "null" && echo "task route OK"
```
**Expected:** вывод `task route OK`
**Extract:** `id` → `$TASK_ID`

### Step 6 — RISK попал в открытые риски
```bash
RISK_ID=$(curl -s "http://localhost:8082/api/risks?status=OPEN" \
  | jq -r --arg title "$RUN_ID RISK title" '.[] | select(.title == $title) | .id' | head -1)
echo "RISK_ID=$RISK_ID"
test -n "$RISK_ID" && test "$RISK_ID" != "null" && echo "risk route OK"
```
**Expected:** вывод `risk route OK`
**Extract:** `id` → `$RISK_ID`

### Step 7 — NOTE попал в /api/notes
```bash
NOTE_ID=$(curl -s "http://localhost:8082/api/notes?tags=mock&limit=100" \
  | jq -r --arg body "$RUN_ID Информация к сведению" '.[] | select(.text == $body and .tags == "capture,mock") | .id' | head -1)
echo "NOTE_ID=$NOTE_ID"
test -n "$NOTE_ID" && test "$NOTE_ID" != "null" && echo "note route OK"
```
**Expected:** вывод `note route OK`
**Extract:** `id` → `$NOTE_ID`

### Step 8 — QUESTION попал в /api/questions
```bash
QUESTION_ID=$(curl -s "http://localhost:8082/api/questions?status=OPEN" \
  | jq -r --arg title "$RUN_ID QUESTION title" '.[] | select(.title == $title) | .id' | head -1)
echo "QUESTION_ID=$QUESTION_ID"
test -n "$QUESTION_ID" && test "$QUESTION_ID" != "null" && echo "question route OK"
```
**Expected:** вывод `question route OK`
**Extract:** `id` → `$QUESTION_ID`

### Step 9 — PERSON_NOTE попал в заметки по имени
```bash
PERSON_ENCODED=$(python3 -c "import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1] + ' Person'))" "$RUN_ID")
PERSON_NOTE_COUNT=$(curl -s "http://localhost:8082/api/people/name/$PERSON_ENCODED/notes" \
  | jq --arg body "$RUN_ID Хочет больше архитектурных задач" '[.[] | select(.note == $body)] | length')
echo "PERSON_NOTE_COUNT=$PERSON_NOTE_COUNT"
test "$PERSON_NOTE_COUNT" = "1" && echo "person note route OK"
```
**Expected:** вывод `person note route OK`

### Step 10 — KNOWLEDGE попал в rag-inbox/captures
```bash
grep -R "$RUN_ID KNOWLEDGE title" JavaRagService/rag-inbox/captures >/dev/null && echo "knowledge route OK"
```
**Expected:** вывод `knowledge route OK`

### Step 11 — JOURNAL попал в daily journal
```bash
grep -R "$RUN_ID JOURNAL title" workspace/08_daily_journal >/dev/null && echo "journal route OK"
```
**Expected:** вывод `journal route OK`

### Step 12 — В очереди за сегодня не осталось файлов этого batch
```bash
LEFT=$(grep -R "$RUN_ID" "$CAPTURE_DIR"/*.md 2>/dev/null | wc -l)
echo "LEFT=$LEFT"
test "$LEFT" = "0" && echo "queue empty OK"
```
**Expected:** вывод `queue empty OK`

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/delete" > /dev/null 2>&1 || true
curl -s -X DELETE "http://localhost:8082/api/risks/$RISK_ID" > /dev/null 2>&1 || true
rm -f "$PROCESSED_DIR/$(basename "$TASK_FILE")" \
      "$PROCESSED_DIR/$(basename "$RISK_FILE")" \
      "$PROCESSED_DIR/$(basename "$NOTE_FILE")" \
      "$PROCESSED_DIR/$(basename "$QUESTION_FILE")" \
      "$PROCESSED_DIR/$(basename "$PERSON_FILE")" \
      "$PROCESSED_DIR/$(basename "$KNOWLEDGE_FILE")" \
      "$PROCESSED_DIR/$(basename "$JOURNAL_FILE")" 2>/dev/null || true
find JavaRagService/rag-inbox/captures -type f -name "*.md" -exec grep -l "$RUN_ID" {} \; | xargs -r rm -f
find workspace/08_daily_journal -type f -name "*.md" -exec grep -l "$RUN_ID" {} \; | xargs -r sed -i "/$RUN_ID/d"
echo "Cleanup done for $RUN_ID. NOTE and QUESTION rows remain as harmless E2E markers."
```
