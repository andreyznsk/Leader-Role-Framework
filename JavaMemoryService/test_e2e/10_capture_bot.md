# Scenario: Capture Bot — raw capture and notes

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0 (CR-MEM-004)

## Описание
Проверить базовый Capture Bot:
- `POST /api/capture` сохраняет raw заметку без классификации
- файл появляется в `capture-inbox/YYYY-MM-DD/`
- `POST /api/notes` сохраняет NOTE в PostgreSQL
- `GET /api/notes?tags=...` фильтрует заметки
- `/ui/notes` доступна

Классификация через `claude --print` не проверяется в этом сценарии, чтобы тест
оставался детерминированным и не зависел от локальной авторизации Claude.

## Preconditions
- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432
- `jq` установлен

## Steps

### Step 1 — Очистить старые E2E capture-файлы за сегодня
```bash
TODAY=$(date +%Y-%m-%d)
mkdir -p "capture-inbox/$TODAY"
rm -f "capture-inbox/$TODAY"/e2e-capture-* 2>/dev/null || true
echo "Capture dir: capture-inbox/$TODAY"
```
**Expected:** команда завершается без ошибки

### Step 2 — Сохранить raw заметку через POST /api/capture
```bash
CAPTURE_TEXT="E2E: capture raw note $(date +%s)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/capture \
  -H "Content-Type: application/json" \
  -d "{
    \"text\": \"$CAPTURE_TEXT\",
    \"source\": \"manual\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
CAPTURE_FILE=$(echo "$BODY" | jq -r '.file')
CAPTURE_ID=$(echo "$BODY" | jq -r '.captureId')
echo "HTTP: $HTTP_CODE | Capture ID: $CAPTURE_ID | File: $CAPTURE_FILE"
```
**Expected:** HTTP 200, тело содержит `"saved":true`, поле `file` не пустое
**Extract:** `file` → `$CAPTURE_FILE`, `captureId` → `$CAPTURE_ID`

### Step 3 — Capture-файл существует и содержит front matter
```bash
test -f "$CAPTURE_FILE" && \
grep -q '^---$' "$CAPTURE_FILE" && \
grep -q '^date: ' "$CAPTURE_FILE" && \
grep -q '^source: manual$' "$CAPTURE_FILE" && \
grep -q "$CAPTURE_TEXT" "$CAPTURE_FILE" && \
echo "capture file OK"
```
**Expected:** вывод `capture file OK`

### Step 4 — Capture виден в /api/capture/today по ID
```bash
curl -s http://localhost:8082/api/capture/today \
  | jq '[.[] | select(.id == '$CAPTURE_ID' and .rawText == "'$CAPTURE_TEXT'" and .status == "PENDING")] | length'
```
**Expected:** результат `1`

### Step 5 — Создать NOTE через /api/notes
```bash
NOTE_TEXT="E2E: note from capture bot $(date +%s)"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/notes \
  -H "Content-Type: application/json" \
  -d "{
    \"text\": \"$NOTE_TEXT\",
    \"tags\": \"e2e,capture\",
    \"source\": \"capture\"
  }")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
NOTE_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | Note ID: $NOTE_ID"
```
**Expected:** HTTP 201, `"source":"capture"`, `"tags":"e2e,capture"`
**Extract:** `id` → `$NOTE_ID`

### Step 6 — NOTE находится через tag-фильтр
```bash
curl -s "http://localhost:8082/api/notes?tags=e2e&limit=50" \
  | jq '[.[] | select(.id == '$NOTE_ID' and .text == "'$NOTE_TEXT'")] | length'
```
**Expected:** результат `1`

### Step 7 — UI /ui/notes доступна и содержит заметку
```bash
curl -s http://localhost:8082/ui/notes | grep -q "$NOTE_TEXT" && echo "notes UI OK"
```
**Expected:** вывод `notes UI OK`

## Cleanup
```bash
# У notes пока нет delete endpoint, запись остаётся как E2E-маркер.
# Capture-файл оставляем в очереди, чтобы можно было вручную проверить process-now.
echo "Cleanup skipped: note $NOTE_ID and capture $CAPTURE_ID are harmless E2E markers"
```
