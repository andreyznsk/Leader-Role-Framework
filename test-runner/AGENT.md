# LeaderOS Test Runner — AGENT.md

## Роль

Ты — автоматический тест-инженер LeaderOS.  
Запускаешься вручную из корня `Leader-Role-Framework/`.  
Твоя цель: прогнать E2E сценарии по указанным модулям и составить отчёт.

---

## Окружение

```
Leader-Role-Framework/          ← ROOT (твоя рабочая директория)
├── test-runner/
│   ├── AGENT.md                ← этот файл
│   ├── build.sh                ← сборка JAR-ов
│   ├── start-services.sh       ← запуск сервисов
│   ├── stop-services.sh        ← остановка сервисов
│   ├── healthcheck.sh          ← проверка здоровья
│   └── reports/                ← сюда пишешь отчёт
│
├── logs/                       ← логи всех сервисов
│   ├── JavaMemoryService.log
│   ├── JavaMailAgent.log
│   ├── JavaRagService.log
│   ├── build-JavaMemoryService.log
│   └── *.pid                   ← PID запущенных процессов
│
├── JavaMemoryService/
│   └── test_e2e/               ← E2E сценарии
├── JavaMailAgent/
│   └── test_e2e/
└── JavaRagService/
    └── test_e2e/
```

---

## Инфраструктура

Перед тестами Docker-инфраструктура должна быть запущена:

```bash
docker compose up -d
```

Что должно работать:
| Сервис | Хост | Порт |
|--------|------|------|
| PostgreSQL | localhost | 5432 |
| OpenSearch | localhost | 9200 |
| OpenSearch Dashboards | localhost | 5601 |
| Maildev UI + API | localhost | 1080 |
| Maildev SMTP | localhost | 1025 |
| Ollama | localhost | 11434 |

Проверить готовность:
```bash
./test-runner/healthcheck.sh
```

---

## Java-сервисы

| Сервис | Порт | JAR | Профиль |
|--------|------|-----|---------|
| JavaMemoryService | 8082 | `JavaMemoryService/target/memory-service*.jar` | local |
| JavaRagService | 8081 | `JavaRagService/target/rag-service*.jar` | local |
| JavaMailAgent | 8080 | `JavaMailAgent/target/mail-agent*.jar` | local |

### Сборка
```bash
./test-runner/build.sh
# или один сервис:
./test-runner/build.sh --service JavaMemoryService
```

### Запуск

Внимание все запуски только с профилем local 

```bash
./test-runner/start-services.sh
# или один сервис:
./test-runner/start-services.sh --service JavaMemoryService
```

### Остановка
```bash
./test-runner/stop-services.sh
```

### Логи — куда смотреть
```bash
# Живой лог:
tail -f logs/JavaMemoryService.log
tail -f logs/JavaMailAgent.log
tail -f logs/JavaRagService.log

# Последние ошибки:
grep -i "ERROR\|Exception" logs/JavaMemoryService.log | tail -20

# Ошибки сборки:
cat logs/build-JavaMemoryService.log
```

### Health check endpoint-ы
```bash
curl http://localhost:8082/actuator/health   # JavaMemoryService
curl http://localhost:8080/actuator/health   # JavaMailAgent
curl http://localhost:8081/actuator/health   # JavaRagService
```

---

## Формат сценария test_e2e/*.md

Каждый файл — один сценарий. Структура:

```
# Scenario: <название>

**service:** <JavaMemoryService | JavaMailAgent | JavaRagService>
**port:** <порт>
**priority:** <CRITICAL | HIGH | MEDIUM | LOW>
**depends_on:** <postgres | maildev | opensearch | ollama>

## Preconditions
- что должно быть запущено

## Steps

### Step N — <описание>
```bash
<команда curl или bash>
```
**Expected:** <что ожидаем — HTTP код, строка в теле>
**Extract:** `<переменная>` из ответа → `$VAR_NAME`  ← если нужно передать в след. шаг

## Cleanup
```bash
<команды удаления тестовых данных>
```
```

---

## Алгоритм тестирования

### 1. Проверить инфраструктуру
```bash
./test-runner/healthcheck.sh
```
Если что-то ❌ — зафиксировать, не идти дальше по этому сервису.

### 2. Собрать сервисы (если JAR-ов нет или устарели)
```bash
./test-runner/build.sh
```
При BUILD FAILED — зафиксировать в отчёте, пропустить сценарии этого сервиса.

### 3. Запустить сервисы
```bash
./test-runner/start-services.sh
```
Подождать 10-15 секунд, затем повторить healthcheck.  
При STARTUP FAILED — смотреть `tail -30 logs/{Service}.log`, зафиксировать ошибку.

### 4. Прогнать сценарии

Для каждого файла в `*/test_e2e/*.md` (в алфавитном порядке):
- Прочитать сценарий
- Выполнить каждый Step последовательно
- Если шаг содержит `**Extract:**` — извлечь значение через `jq` и сохранить в переменную
- Сравнить результат с `**Expected:**`
- Записать: **PASS** или **FAIL** + фактический ответ при FAIL
- Выполнить **Cleanup** в любом случае (даже при FAIL)

### 5. Написать отчёт
Сохранить в `test-runner/reports/TEST-REPORT-{YYYY-MM-DD}.md`

---

## Формат отчёта

```markdown
# TEST-REPORT-{date}

**Запуск:** {datetime}
**Профиль:** local
**Инициатор:** ручной запуск

---

## Summary

| Сервис | Сборка | Запуск | Сценариев | PASS | FAIL | SKIP |
|--------|--------|--------|-----------|------|------|------|
| JavaMemoryService | ✅ | ✅ | 4 | 3 | 1 | 0 |
| JavaMailAgent | ✅ | ✅ | 2 | 2 | 0 | 0 |
| JavaRagService | ❌ BUILD_FAILED | — | 2 | 0 | 0 | 2 |
| **Итого** | | | **8** | **5** | **1** | **2** |

---

## JavaMemoryService

### ✅ 01_health_check — PASS (0.4s)
### ✅ 02_pending_task_flow — PASS (1.8s)
### ✅ 03_mcp_tools_list — PASS (0.6s)

### ❌ 04_context_no_pending — FAIL

**Упавший шаг:** Step 2 — getContext через MCP  
**Expected:** тело НЕ содержит `"ctx-test-001"`  
**Actual HTTP:** 200  
**Actual body (фрагмент):**
```json
{"tasks":[{"emailId":"ctx-test-001",...}]}
```
**Вероятная причина:** getContext не фильтрует PENDING задачи

---

## Рекомендации к BUGFIX_CR

### CR-MEM-BUGFIX-001 — getContext включает PENDING задачи
- **Файл:** `JavaMemoryService/src/main/java/.../service/ContextService.java`
- **Проблема:** метод `buildContext()` не исключает задачи со статусом PENDING
- **Фикс:** добавить фильтр `status != 'PENDING'` в запрос задач
- **Сценарий для проверки:** `JavaMemoryService/test_e2e/04_context_no_pending.md`
```

---

## Правила

- **Не останавливаться при FAIL** — прогонять все сценарии до конца
- **Всегда выполнять Cleanup** — даже если шаг упал
- **Таймаут curl** — `--max-time 10` на каждый запрос
- **jq для Extract** — `echo "$RESPONSE" | jq -r '.id'`
- **При BUILD_FAILED** — пропустить все сценарии сервиса, записать SKIP
- **При STARTUP_FAILED** — показать последние 30 строк лога в отчёте

---

## Примеры команд для сценариев

### Создать задачу и извлечь id
```bash
RESPONSE=$(curl -s -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","emailId":"e2e-001","sender":"test@test.com","priority":"HIGH"}')
TASK_ID=$(echo "$RESPONSE" | jq -r '.id')
echo "Created task id: $TASK_ID"
```

### Проверить HTTP код
```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/actuator/health)
[[ "$HTTP_CODE" == "200" ]] && echo "PASS" || echo "FAIL: got $HTTP_CODE"
```

### Проверить строку в теле
```bash
BODY=$(curl -s http://localhost:8082/api/tasks/pending)
echo "$BODY" | grep -q '"emailId":"e2e-001"' && echo "PASS" || echo "FAIL"
```

### MCP tool call
```bash
curl -s -X POST http://localhost:8082/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

---

## Промпт для запуска агента вручную

Когда запускаешь Claude Code вручную, используй этот промпт:

```
Прочитай test-runner/AGENT.md и протестируй все модули.
Прогони все E2E сценарии из */test_e2e/*.
Сохрани отчёт в test-runner/reports/TEST-REPORT-{сегодняшняя дата}.md
```

Или для одного модуля:
```
Прочитай test-runner/AGENT.md и протестируй JavaMemoryService.
Прогони сценарии из JavaMemoryService/test_e2e/*.
Сохрани отчёт в test-runner/reports/TEST-REPORT-{дата}-memory.md
```
