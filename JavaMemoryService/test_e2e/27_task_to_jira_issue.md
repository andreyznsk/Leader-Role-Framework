# Scenario: Create Jira issue from Today task

**service:** JavaMemoryService  
**port:** 8082  
**priority:** HIGH  
**depends_on:** postgres

## Описание
Проверяет CR-MEM-035: управляемый flow создания Jira issue из задачи `/ui/today`,
startup status `DISABLED | AVAILABLE | UNAVAILABLE`, server-side allowlist проектов,
предзаполненный modal context, сохранение связи в `memory.task_external_issues` и защиту от дублей.

## Preconditions
- JavaMemoryService запущен на `:8082`
- Jira integration включена через application properties или environment:
  - `JIRA_ENABLED=true`
  - `JIRA_BASE_URL=...`
  - `JIRA_TOKEN=...`
  - `JIRA_DEFAULT_PROJECT=...`
  - `JIRA_ALLOWED_PROJECTS=...`
- Для локального stub-flow допустимо использовать тестовый Jira stub на `http://localhost:19997`

## Steps

### Step 1 — Создать локальную задачу Today
```bash
TODAY=$(date +%Y-%m-%d)
TASK_ID=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"E2E Jira Flow Task\",\"date\":\"$TODAY\",\"priority\":\"HIGH\",\"source\":\"MANUAL\"}" | jq -r '.id')
echo "$TASK_ID"
```
**Expected:** `TASK_ID` не пустой

### Step 2 — Получить Jira context
```bash
curl -s "http://localhost:8082/api/tasks/$TASK_ID/jira/context" | jq
```
**Expected:**
- `integrationStatus` = `AVAILABLE` либо `DISABLED/UNAVAILABLE` с безопасным `message`
- `summary` предзаполнен заголовком задачи
- `description` содержит описание задачи
- `projects[]` содержит только allowlist

### Step 3 — Создать Jira issue
```bash
RESP=$(curl -s -w "\n%{http_code}" -X POST "http://localhost:8082/api/tasks/$TASK_ID/jira/issues" \
  -H "Content-Type: application/json" \
  -d '{
        "projectKey":"ENG",
        "issueTypeId":"3",
        "summary":"E2E Jira Flow Task",
        "description":"Created from LeaderOS E2E flow"
      }')
HTTP_CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
echo "$BODY" | jq
echo "HTTP: $HTTP_CODE"
```
**Expected:** `HTTP: 201`, в ответе есть `issue.key`, `issue.url`, `created=true`

### Step 4 — Повторный create не создаёт дубль
```bash
curl -s -X POST "http://localhost:8082/api/tasks/$TASK_ID/jira/issues" \
  -H "Content-Type: application/json" \
  -d '{
        "projectKey":"ENG",
        "issueTypeId":"3",
        "summary":"E2E Jira Flow Task",
        "description":"Created from LeaderOS E2E flow"
      }' | jq
```
**Expected:** `alreadyLinked=true`, возвращается тот же `issue.key`

### Step 5 — `/ui/today` показывает action или ссылку на Jira
```bash
curl -s "http://localhost:8082/ui/today" > /tmp/e2e_today_jira.html
grep -c "jiraIssueModal" /tmp/e2e_today_jira.html
grep -c "Создать задачу в Jira" /tmp/e2e_today_jira.html
```
**Expected:** HTML содержит modal и action для Jira flow

### Step 6 — `/ui/tasks/{id}/edit` показывает Jira block
```bash
curl -s "http://localhost:8082/ui/tasks/$TASK_ID/edit" > /tmp/e2e_task_edit_jira.html
grep -c "task-edit-jira-modal" /tmp/e2e_task_edit_jira.html
grep -c "Создать задачу в Jira" /tmp/e2e_task_edit_jira.html
```
**Expected:** HTML содержит Jira modal/block в edit flow

## Cleanup
```bash
rm -f /tmp/e2e_today_jira.html /tmp/e2e_task_edit_jira.html
```
