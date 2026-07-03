# E2E: Toast UI WYSIWYG editor for task description (CR-MEM-029)

## Preconditions

- JavaMemoryService is running on `http://localhost:8082`
- Self-hosted assets available at `/vendor/toastui/toastui-editor-all.min.js`,
  `/vendor/toastui/toastui-editor.min.css`, `/vendor/toastui/toastui-editor-dark.min.css`

## Step 1 - Create a task

```bash
TASK_JSON=$(curl -s -X POST "http://localhost:8082/api/tasks" \
  -H "Content-Type: application/json" \
  -d '{
    "title":"Проверить Toast UI редактор",
    "date":"'"$(date +%F)"'",
    "priority":"NORMAL",
    "source":"MANUAL"
  }')
TASK_ID=$(echo "$TASK_JSON" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "$TASK_ID"
```

Expected: `201 Created`, `TASK_ID` extracted.

## Step 2 - Vendor assets load without a CDN

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8082/vendor/toastui/toastui-editor-all.min.js"
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8082/vendor/toastui/toastui-editor.min.css"
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8082/vendor/toastui/toastui-editor-dark.min.css"
```

Expected: all three return `200`.

## Step 3 - Open the task editor in a browser (Playwright)

See `tests/task-toastui-editor.spec.js`:

1. Открыть `/ui/tasks/{id}/edit` — WYSIWYG-редактор (`[data-testid="task-description-editor"]`) отрисован.
2. Набрать текст с заголовком и списком в WYSIWYG-режиме.
3. Нажать "Сохранить".
4. `GET /api/tasks/{id}/description` (`Accept: text/plain`) — содержит Markdown-заголовок (`## ...`) и элемент списка (`- ...`).
5. Перезагрузить страницу — контент восстановлен в редакторе.

## Step 4 - Markdown export still works

```bash
curl -i "http://localhost:8082/api/tasks/$TASK_ID/description/export-md"
```

Expected: `200 OK`, `Content-Type: text/markdown`, body contains the saved heading text.

## Step 5 - Global search still indexes the WYSIWYG-authored content

```bash
curl -s -X POST "http://localhost:8082/api/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"toast редактор","layers":["TASK"],"mode":"QUICK","limit":10}'
```

Expected: task is present in search results.

## Cleanup

```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/archive" > /dev/null
```
