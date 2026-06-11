# Scenario: People — карточка команды и заметки

**service:** JavaMemoryService
**port:** 8082
**priority:** MEDIUM
**depends_on:** postgres
**version:** 2.0 (data isolation fix — поиск по ID, URL-encoded query)

## Описание
Создать карточку → найти по ID → добавить заметки → прочитать заметки → обновить карточку.
Поиск по имени проверяется через URL-encoded query param.
Все count-проверки по конкретному ID — устойчиво к накопленным данным.

## Steps

### Step 1 — Создать карточку человека
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/people \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "E2E Иванов Тестовый",
    "login": "e2e.ivanov.test",
    "email": "e2e.ivanov.test@company.ru",
    "domain": "Backend / payments",
    "currentTask": "Рефакторинг batch-процессора",
    "capacitySprint": 40
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
PERSON_ID=$(echo "$BODY" | jq -r '.id')
echo "HTTP: $HTTP_CODE | ID: $PERSON_ID | Name: $(echo "$BODY" | jq -r '.fullName')"
```
**Expected:** HTTP 201, `"fullName":"E2E Иванов Тестовый"`
**Extract:** `id` → `$PERSON_ID`

### Step 2 — Найти по ID в общем списке
```bash
curl -s http://localhost:8082/api/people \
  | jq '[.[] | select(.id == '$PERSON_ID')] | length'
```
**Expected:** результат `1`

### Step 3 — Поиск по имени через URL-encoded query
```bash
NAME_ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('E2E Иванов Тестовый'))")
curl -s "http://localhost:8082/api/people?name=$NAME_ENCODED" \
  | jq '[.[] | select(.id == '$PERSON_ID')] | length'
```
**Expected:** результат `1`

### Step 4 — Добавить первую заметку
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "http://localhost:8082/api/people/$PERSON_ID/notes" \
  -H "Content-Type: application/json" \
  -d '{
    "note": "На 1-1 сказал что хочет больше архитектурных задач. Устал от поддержки.",
    "tags": "motivation,career"
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
NOTE1_ID=$(echo "$RESPONSE" | head -n -1 | jq -r '.id')
echo "HTTP: $HTTP_CODE | Note ID: $NOTE1_ID"
```
**Expected:** HTTP 201
**Extract:** `id` → `$NOTE1_ID`

### Step 5 — Добавить вторую заметку
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" \
  -X POST "http://localhost:8082/api/people/$PERSON_ID/notes" \
  -H "Content-Type: application/json" \
  -d '{
    "note": "Единственный кто знает деплой payments в prod. Bus factor риск.",
    "tags": "risk,bus-factor"
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
echo "HTTP: $HTTP_CODE"
```
**Expected:** HTTP 201

### Step 6 — Получить заметки по personId — ровно 2 для этого person
```bash
NOTES=$(curl -s "http://localhost:8082/api/people/$PERSON_ID/notes")
echo "$NOTES" | jq 'length'
echo "$NOTES" | jq '[.[] | {note, tags, createdAt}]'
```
**Expected:** HTTP 200, массив длиной `2`, у каждого есть `createdAt`

### Step 7 — Обновить карточку (новая текущая задача)
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT "http://localhost:8082/api/people/$PERSON_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "E2E Иванов Тестовый",
    "login": "e2e.ivanov.test",
    "email": "e2e.ivanov.test@company.ru",
    "domain": "Backend / payments",
    "currentTask": "Архитектурный дизайн payments v2",
    "capacitySprint": 40
  }')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
echo "HTTP: $HTTP_CODE | currentTask: $(echo "$RESPONSE" | head -n -1 | jq -r '.currentTask')"
```
**Expected:** HTTP 200, `"currentTask":"Архитектурный дизайн payments v2"`

### Step 8 — getContext содержит recent people notes (проверка по personId)
```bash
curl -s http://localhost:8082/api/context \
  | jq '[.recentPeopleNotes[]? | select(.personId == '$PERSON_ID')] | length'
```
**Expected:** результат >= 1

### Step 9 — UI /ui/people доступна
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/ui/people
```
**Expected:** HTTP 200

## Cleanup
```bash
# Hard delete person (каскадно удалит notes — требует CR-MEM-BUGFIX-006)
curl -s -X DELETE "http://localhost:8082/api/people/$PERSON_ID" > /dev/null 2>&1 || \
  echo "DELETE not yet implemented — person $PERSON_ID stays in DB (cleanup manually if needed)"
```
