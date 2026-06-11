# Задача для Claude Code

**Запускать из:** `Leader-Role-Framework/`  
**Читать перед работой:** `ARCHITECTURE.md`, `RFC-test-runner.md` (в корне)

---

## Что нужно реализовать

Создать суб-агент тестирования `test-runner` по RFC.  
Реализовать полностью, без скаффолдинга — сразу рабочий код.

---

## Файлы для создания

### 1. `test-runner/AGENT.md`

Инструкции агента — скопировать из секции 4 RFC-test-runner.md дословно.  
Это файл, который `claude --print` будет читать при запуске.

---

### 2. `test-runner/run-tests.sh`

Скопировать из секции 6 RFC-test-runner.md.  
Добавить обработку аргументов:

```bash
# Поддерживаемые флаги:
# --no-docker-down     не выполнять docker compose down после тестов
# --service NAME       прогнать только один сервис (JavaMemoryService | JavaMailAgent | JavaRagService)
# --skip-build         не пересобирать JAR (если уже собраны)
```

Сделать исполняемым: `chmod +x test-runner/run-tests.sh`

Создать папку: `test-runner/reports/.gitkeep`

---

### 3. `JavaMemoryService/test_e2e/01_health_check.md`

```markdown
# Scenario: Health Check

**service:** JavaMemoryService
**port:** 8082
**priority:** CRITICAL
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Actuator health
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health
```
**Expected:** HTTP 200

### Step 2 — Тело health содержит status UP
```bash
curl -s http://localhost:8082/actuator/health
```
**Expected:** тело содержит `"status":"UP"`

## Cleanup
# ничего не требуется
```

---

### 4. `JavaMemoryService/test_e2e/02_pending_task_flow.md`

Полный цикл: создать PENDING → проверить список → подтвердить → проверить статус TODO → отклонить оставшиеся.

```markdown
# Scenario: Pending Task Flow

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432

## Steps

### Step 1 — Создать PENDING задачу
```bash
curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{"title":"E2E Test Task","description":"Automated E2E test","emailId":"e2e-test-001","sender":"e2e@test.com","priority":"HIGH"}'
```
**Expected:** HTTP 201, тело содержит `"status":"PENDING"` и `"emailId":"e2e-test-001"`
**Extract:** `id` из тела ответа → сохранить как `$TASK_ID`

### Step 2 — GET /api/tasks/pending — задача видна
```bash
curl -s http://localhost:8082/api/tasks/pending
```
**Expected:** HTTP 200, массив содержит объект с `"emailId":"e2e-test-001"`

### Step 3 — Подтвердить задачу (PENDING → TODO)
```bash
curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/tasks/$TASK_ID/confirm
```
**Expected:** HTTP 200, тело содержит `"status":"TODO"`

### Step 4 — Задача больше не в PENDING
```bash
curl -s http://localhost:8082/api/tasks/pending
```
**Expected:** HTTP 200, массив НЕ содержит `"emailId":"e2e-test-001"`

### Step 5 — Задача видна в задачах дня
```bash
DATE=$(date +%Y-%m-%d)
curl -s "http://localhost:8082/api/tasks?date=$DATE&status=TODO"
```
**Expected:** HTTP 200, массив содержит `"emailId":"e2e-test-001"`

## Cleanup
```bash
# Удалить тестовую задачу
curl -s -X POST http://localhost:8082/api/tasks/$TASK_ID/reject
```
```

---

### 5. `JavaMemoryService/test_e2e/03_mcp_tools_list.md`

```markdown
# Scenario: MCP Tools List

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — MCP tools/list handshake
```bash
curl -s -X POST http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```
**Expected:** HTTP 200, тело содержит все обязательные tools:
- `getContext`
- `getTasks`
- `createTask`
- `markTaskDone`
- `createIncident`
- `addRisk`
- `addPeopleNote`

## Cleanup
# ничего не требуется
```

---

### 6. `JavaMemoryService/test_e2e/04_context_no_pending.md`

```markdown
# Scenario: Context Does Not Include Pending Tasks

**service:** JavaMemoryService
**port:** 8082
**priority:** MEDIUM
**depends_on:** postgres

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Создать PENDING задачу
```bash
curl -s -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{"title":"Context Test Pending","emailId":"ctx-test-001","sender":"test@test.com","priority":"LOW"}'
```
**Expected:** HTTP 201
**Extract:** `id` → `$CTX_TASK_ID`

### Step 2 — getContext через MCP — PENDING задача НЕ входит
```bash
curl -s -X POST http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"getContext","arguments":{}}}'
```
**Expected:** HTTP 200, тело НЕ содержит `"ctx-test-001"` в контексте

## Cleanup
```bash
curl -s -X POST http://localhost:8082/api/tasks/$CTX_TASK_ID/reject
```
```

---

### 7. `JavaMailAgent/test_e2e/01_health_check.md`

```markdown
# Scenario: Health Check

**service:** JavaMailAgent
**port:** 8080
**priority:** CRITICAL
**depends_on:** maildev

## Steps

### Step 1 — Actuator health
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health
```
**Expected:** HTTP 200

### Step 2 — UI status page доступна
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ui/status
```
**Expected:** HTTP 200

## Cleanup
# ничего не требуется
```

---

### 8. `JavaMailAgent/test_e2e/02_maildev_connection.md`

```markdown
# Scenario: Maildev Connection

**service:** JavaMailAgent
**port:** 8080
**priority:** HIGH
**depends_on:** maildev

## Preconditions
- Maildev запущен на :1080
- JavaMailAgent запущен с профилем local

## Steps

### Step 1 — Maildev доступен
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:1080
```
**Expected:** HTTP 200

### Step 2 — Maildev API — список писем пуст или возвращает массив
```bash
curl -s http://localhost:1080/email
```
**Expected:** HTTP 200, тело является JSON-массивом (может быть пустым `[]`)

### Step 3 — Отправить тестовое письмо через SMTP
```bash
curl -s --url "smtp://localhost:1025" \
  --mail-from "sender@test.com" \
  --mail-rcpt "me@test.com" \
  --upload-file - <<'EOF'
Subject: E2E Test Mail
From: sender@test.com
To: me@test.com

This is an automated E2E test message.
EOF
```
**Expected:** команда завершается с кодом 0 (без ошибки)

### Step 4 — Письмо появилось в Maildev
```bash
sleep 2
curl -s http://localhost:1080/email
```
**Expected:** HTTP 200, массив содержит хотя бы одно письмо с subject содержащим `E2E Test Mail`

## Cleanup
```bash
# Удалить все тестовые письма
curl -s -X DELETE http://localhost:1080/email/all
```
```

---

### 9. `JavaRagService/test_e2e/01_health_check.md`

```markdown
# Scenario: Health Check

**service:** JavaRagService
**port:** 8081
**priority:** CRITICAL
**depends_on:** opensearch, postgres

## Steps

### Step 1 — RAG status endpoint
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/mcp/rag_status
```
**Expected:** HTTP 200

### Step 2 — OpenSearch доступен
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:9200
```
**Expected:** HTTP 200

## Cleanup
# ничего не требуется
```

---

### 10. `JavaRagService/test_e2e/02_index_and_search.md`

```markdown
# Scenario: Index Document and Search

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** opensearch, postgres, ollama

## Preconditions
- JavaRagService запущен на :8081
- OpenSearch доступен на :9200
- Ollama запущен на :11434 с моделью multilingual-e5-large

## Steps

### Step 1 — Создать тестовый документ в rag-inbox
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-test-doc.md <<'EOF'
# E2E Test Document

Этот документ создан автоматически для E2E тестирования RAG-сервиса.
Содержит ключевые слова: тестирование, индексация, семантический поиск.
EOF
```
**Expected:** файл создан

### Step 2 — Индексировать через MCP tool
```bash
curl -s -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -d '{"method":"rag_index","params":{"file_path":"rag-inbox/e2e-test-doc.md"}}'
```
**Expected:** HTTP 200, тело содержит `chunks_added` > 0

### Step 3 — Семантический поиск
```bash
curl -s -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"тестирование RAG индексация","top_k":3}'
```
**Expected:** HTTP 200, результаты содержат source `rag-inbox/e2e-test-doc.md`

## Cleanup
```bash
rm -f rag-inbox/e2e-test-doc.md
```
```

---

## Проверки после реализации

После создания всех файлов проверить:

```bash
# Структура создана
ls -la test-runner/
ls -la JavaMemoryService/test_e2e/
ls -la JavaMailAgent/test_e2e/
ls -la JavaRagService/test_e2e/

# Скрипт исполняемый
ls -la test-runner/run-tests.sh

# Синтаксис скрипта
bash -n test-runner/run-tests.sh
```

---

## Что НЕ делать

- Не запускать `docker compose up` — только создать файлы
- Не запускать сами тесты — только создать инфраструктуру
- Не менять RFC-*.md и ARCHITECTURE.md
- Не трогать существующие сервисы
