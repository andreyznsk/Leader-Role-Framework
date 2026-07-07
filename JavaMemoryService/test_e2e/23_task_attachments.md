# Scenario: Task Attachments (files & links)

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Описание
Проверяет CR-MEM-030: вложения к задачам (файлы на файловой системе + внешние ссылки),
скачивание файла, защиту от path traversal, отклонение неподдерживаемого MIME и
превышения размера, а также удаление вложения.

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать задачу
```bash
TODAY=$(date +%Y-%m-%d)
BODY=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\": \"E2E Attachments Task\", \"date\": \"$TODAY\", \"priority\": \"NORMAL\", \"source\": \"MANUAL\"}")
TASK_ID=$(echo "$BODY" | jq -r '.id')
echo "$BODY" | jq '{id, title}'
```
**Expected:** задача создана, `id` не пустой

### Step 2 — Загрузить файл (PNG)
```bash
printf '\x89PNG\r\n\x1a\n' > /tmp/e2e-attachment.png
RESP=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8082/api/tasks/$TASK_ID/attachments" \
  -F "file=@/tmp/e2e-attachment.png;type=image/png")
HTTP_CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
ATTACHMENT_ID=$(echo "$BODY" | jq -r '.id')
echo "$BODY" | jq '{id, kind, filename, mimeType}'
echo "HTTP: $HTTP_CODE"
```
**Expected:** `HTTP: 201`, `"kind":"FILE"`, `"filename":"e2e-attachment.png"`

### Step 3 — Скачать содержимое вложения
```bash
curl -s -o /tmp/e2e-attachment-downloaded.png -w "%{http_code}\n" \
  "http://localhost:8082/api/tasks/$TASK_ID/attachments/$ATTACHMENT_ID/content"
diff /tmp/e2e-attachment.png /tmp/e2e-attachment-downloaded.png && echo "BYTES MATCH"
```
**Expected:** `200`, `BYTES MATCH`

### Step 4 — Добавить внешнюю ссылку (LINK)
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/attachments/link" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://drive.google.com/file/d/abc123","title":"Design doc"}' \
  | jq '{kind, url, title}'
```
**Expected:** `"kind":"LINK"`

### Step 5 — Список вложений
```bash
curl -s "http://localhost:8082/api/tasks/$TASK_ID/attachments" | jq 'length'
```
**Expected:** `2`

### Step 6 — Отклонить path traversal в имени файла
```bash
printf 'x' > /tmp/evil.sh
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8082/api/tasks/$TASK_ID/attachments" \
  -F "file=@/tmp/evil.sh;filename=../../evil.sh;type=text/plain"
```
**Expected:** `400`

### Step 7 — Отклонить неподдерживаемый MIME
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8082/api/tasks/$TASK_ID/attachments" \
  -F "file=@/tmp/evil.sh;filename=app.exe;type=application/x-msdownload"
```
**Expected:** `400`

### Step 8 — Отклонить превышение размера (>20MB)
```bash
dd if=/dev/zero of=/tmp/e2e-big.bin bs=1M count=21 2>/dev/null
curl -s -o /dev/null -w "%{http_code}\n" -X POST "http://localhost:8082/api/tasks/$TASK_ID/attachments" \
  -F "file=@/tmp/e2e-big.bin;filename=big.png;type=image/png"
```
**Expected:** `413`

### Step 9 — Удалить вложение
```bash
curl -s -o /dev/null -w "%{http_code}\n" -X DELETE "http://localhost:8082/api/tasks/$TASK_ID/attachments/$ATTACHMENT_ID"
curl -s "http://localhost:8082/api/tasks/$TASK_ID/attachments" | jq 'length'
```
**Expected:** `204`, затем `1` (осталась только LINK-запись)

## Известное ограничение
CR-MEM-030 предполагает "удаление задачи → cleanup файлов", но в текущем коде задачи
удаляются только мягко (`POST /api/tasks/{id}/archive`, статус ARCHIVED) — жёсткого
DELETE для tasks в приложении нет. Поэтому автоматический cleanup файлов вложений при
архивации НЕ реализован (архивная задача не теряет данные и может быть просмотрена).
Это сознательное отклонение от буквального текста CR — требует отдельного решения,
если появится жёсткое удаление задач.

## Cleanup
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/archive" > /dev/null
rm -f /tmp/e2e-attachment.png /tmp/e2e-attachment-downloaded.png /tmp/evil.sh /tmp/e2e-big.bin
```
