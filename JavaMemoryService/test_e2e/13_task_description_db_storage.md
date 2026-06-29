# E2E: Task description DB storage and markdown export

## Preconditions

- JavaMemoryService is running on `http://localhost:8082`
- PostgreSQL or H2 test profile is available

## Step 1 - Create a task

```bash
TASK_JSON=$(curl -s -X POST "http://localhost:8082/api/tasks" \
  -H "Content-Type: application/json" \
  -d '{
    "title":"Подготовить релиз payment-service",
    "date":"'"$(date +%F)"'",
    "priority":"HIGH",
    "source":"MANUAL"
  }')

echo "$TASK_JSON"
TASK_ID=$(echo "$TASK_JSON" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "$TASK_ID"
```

Expected:
- `201 Created`
- task id extracted into `TASK_ID`

## Step 2 - Save markdown description in DB

```bash
curl -i -X PUT "http://localhost:8082/api/tasks/$TASK_ID/description" \
  -H "Content-Type: application/json" \
  -d '{
    "contentMd":"## Context\nblocked, ждём согласование QA, есть риск сдвига релиза"
  }'
```

Expected:
- `200 OK`
- JSON response contains `taskId`, `contentMd`, `contentHash`

## Step 3 - Read markdown description from API

```bash
curl -s "http://localhost:8082/api/tasks/$TASK_ID/description"
```

Expected:
- JSON response contains `contentMd`
- `contentMd` matches the saved markdown

## Step 4 - Search by words that exist only in markdown description

```bash
curl -s -X POST "http://localhost:8082/api/search" \
  -H "Content-Type: application/json" \
  -d '{
    "query":"согласование qa blocked",
    "layers":["TASK"],
    "mode":"QUICK",
    "limit":10
  }'
```

Expected:
- task is present in search results
- `snippet` contains a fragment from markdown description

## Step 5 - Export markdown

```bash
curl -i "http://localhost:8082/api/tasks/$TASK_ID/description/export-md"
```

Expected:
- `200 OK`
- `Content-Type: text/markdown`
- `Content-Disposition` contains `TASK-$TASK_ID.md`
- body contains `blocked, ждём согласование QA`

## Step 6 - Verify no task markdown file was created automatically

```bash
test ! -f "workspace/tasks/TASK-$(printf "%03d" "$TASK_ID").md" && echo "OK"
```

Expected:
- file does not exist unless user explicitly exported it elsewhere
