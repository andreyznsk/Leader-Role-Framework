# Scenario: Task description in DB -> Global Search finds task by description

**service:** JavaMemoryService
**ports:** 8082
**priority:** HIGH
**depends_on:** postgres
**profile:** local or ollama
**cr:** CR-MEM-013

## Описание

Проверяет новый flow для task description:

`create task -> save markdown description in DB -> global search by description-only terms -> result returned with high score`

Ключевая проверка: поиск должен находить задачу по словам, которых нет в title,
но которые есть в markdown-описании.

## Environment

```bash
source e2e-integration/env.sh
```

## Preconditions

- JavaMemoryService запущен на `:8082`
- PostgreSQL доступен
- endpoint `POST /api/search` включён

## Steps

### Step 1 — Проверить health JavaMemoryService

```bash
MS=$(curl -s -o /dev/null -w "%{http_code}" $MS_URL/actuator/health)
echo "MemoryService: $MS"
```

**Expected:** `200`

### Step 2 — Создать задачу

```bash
RUN_ID="it11-$(date +%s)"
TODAY=$(date +%F)

TASK_JSON=$(curl -s -X POST $MS_URL/api/tasks \
  -H "Content-Type: application/json" \
  -d "{
    \"title\":\"Подготовить релиз payment-service $RUN_ID\",
    \"date\":\"$TODAY\",
    \"priority\":\"HIGH\",
    \"source\":\"MANUAL\"
  }")

echo "$TASK_JSON" | jq '{id, title, status, priority}'
TASK_ID=$(echo "$TASK_JSON" | jq -r '.id')
echo "TASK_ID=$TASK_ID | RUN_ID=$RUN_ID"
```

**Expected:**
- HTTP `201`
- `TASK_ID` числовой
- `status = "TODO"`

**Extract:** `$TASK_ID`, `$RUN_ID`, `$TODAY`

### Step 3 — Сохранить markdown-описание задачи в БД

```bash
DESCRIPTION_JSON=$(curl -s -X PUT $MS_URL/api/tasks/$TASK_ID/description \
  -H "Content-Type: application/json" \
  -d "{
    \"contentMd\":\"## Context\n$RUN_ID blocked, ждём согласование qa, есть риск сдвига релиза и зависимость от smoke проверки\"
  }")

echo "$DESCRIPTION_JSON" | jq '{taskId, contentHash, updatedAt, contentMd}'
```

**Expected:**
- HTTP `200`
- `taskId = $TASK_ID`
- `contentHash` непустой

### Step 4 — Убедиться, что описание читается обратно

```bash
READ_JSON=$(curl -s $MS_URL/api/tasks/$TASK_ID/description)
echo "$READ_JSON" | jq '{taskId, contentMd}'
READ_OK=$(echo "$READ_JSON" | jq -r --arg r "$RUN_ID" '.contentMd | contains($r)')
echo "READ_OK=$READ_OK"
```

**Expected:** `READ_OK=true`

### Step 5 — Выполнить global search по словам только из description

```bash
SEARCH_JSON=$(curl -s -X POST $MS_URL/api/search \
  -H "Content-Type: application/json" \
  -d "{
    \"query\":\"согласование qa smoke проверка $RUN_ID\",
    \"layers\":[\"TASK\"],
    \"mode\":\"QUICK\",
    \"limit\":5
  }")

echo "$SEARCH_JSON" | jq '{query, mode, layers, top: (.results[0] // null)}'
```

**Expected:**
- HTTP `200`
- `results` не пустой

### Step 6 — Проверить, что нужная задача найдена и score высокий

```bash
FOUND_COUNT=$(echo "$SEARCH_JSON" | jq --arg id "$TASK_ID" '[.results[] | select(.entityId == $id)] | length')
FOUND_TITLE=$(echo "$SEARCH_JSON" | jq -r --arg id "$TASK_ID" '[.results[] | select(.entityId == $id)] | first | .title // empty')
FOUND_SNIPPET=$(echo "$SEARCH_JSON" | jq -r --arg id "$TASK_ID" '[.results[] | select(.entityId == $id)] | first | .snippet // empty')
FOUND_SCORE=$(echo "$SEARCH_JSON" | jq -r --arg id "$TASK_ID" '[.results[] | select(.entityId == $id)] | first | .score // 0')
FOUND_LAYER=$(echo "$SEARCH_JSON" | jq -r --arg id "$TASK_ID" '[.results[] | select(.entityId == $id)] | first | .layer // empty')
FOUND_MATCHED_FIELDS=$(echo "$SEARCH_JSON" | jq -r --arg id "$TASK_ID" '[.results[] | select(.entityId == $id)] | first | .matchedFields | join(",")')
TOP_ENTITY_ID=$(echo "$SEARCH_JSON" | jq -r '.results[0].entityId // empty')

echo "FOUND_COUNT=$FOUND_COUNT"
echo "FOUND_TITLE=$FOUND_TITLE"
echo "FOUND_LAYER=$FOUND_LAYER"
echo "FOUND_SCORE=$FOUND_SCORE"
echo "FOUND_SNIPPET=$FOUND_SNIPPET"
echo "FOUND_MATCHED_FIELDS=$FOUND_MATCHED_FIELDS"
echo "TOP_ENTITY_ID=$TOP_ENTITY_ID"
```

**Expected:**
- `FOUND_COUNT >= 1`
- `FOUND_LAYER = "TASK"`
- `FOUND_SNIPPET` содержит фрагмент из markdown description
- `FOUND_MATCHED_FIELDS` содержит `contentMd`
- `FOUND_SCORE >= 0.30`
- желательно `TOP_ENTITY_ID = $TASK_ID`

### Step 7 — Явная валидация snippet по описанию

```bash
SNIPPET_HAS_QA=$(echo "$FOUND_SNIPPET" | grep -c "согласование qa" || true)
SNIPPET_HAS_SMOKE=$(echo "$FOUND_SNIPPET" | grep -c "smoke" || true)
echo "SNIPPET_HAS_QA=$SNIPPET_HAS_QA | SNIPPET_HAS_SMOKE=$SNIPPET_HAS_SMOKE"
```

**Expected:** хотя бы одно из значений `>= 1`

## Cleanup

```bash
curl -s -X POST "$MS_URL/api/tasks/$TASK_ID/delete" > /dev/null 2>&1 || true
echo "IT-11 cleanup done"
```
