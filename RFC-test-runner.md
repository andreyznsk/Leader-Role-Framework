# RFC: LeaderOS Test Runner — Суб-агент E2E тестирования

**Версия:** 1.1  
**Дата:** 2026-06-20  
**Статус:** Draft  
**Префикс CR:** `TEST`  
**Автор:** Андрей Зайцев

---

## 1. Назначение

`test-runner` — суб-агент (Claude Code), который:

- Запускается из корня `Leader-Role-Framework/` и видит все модули
- Поднимает инфраструктуру через `docker compose`
- Собирает и запускает каждый Java-сервис в нужном профиле (`local` или `ollama`, в зависимости от сценария)
- Читает E2E сценарии из `test_e2e/` каждого модуля и из общего `e2e-integration/`
- Составляет структурированный отчёт `TEST-REPORT-{date}.md`
- Отчёт передаётся разработчику → баги оформляются через `BUGFIX_CR`

---

## 2. Место в архитектуре

```
Leader-Role-Framework/
├── test-runner/                  ← этот агент
│   ├── AGENT.md                  ← инструкции агента
│   ├── run-tests.sh              ← точка входа
│   └── reports/
│       └── TEST-REPORT-YYYY-MM-DD.md
│
├── JavaMailAgent/
│   └── test_e2e/
│       ├── 01_maildev_connection.md
│       ├── 02_mail_poll_cycle.md
│       └── 03_agent_classification.md
│
├── JavaMemoryService/
│   └── test_e2e/
│       ├── 01_mcp_tools_list.md
│       ├── 02_pending_task_flow.md
│       └── 03_ui_today.md
│
└── JavaRagService/
    └── test_e2e/
        ├── 01_index_document.md
        ├── 02_search_query.md
        └── 03_scheduler_inbox.md
```

---

## 3. Формат сценария (test_e2e/*.md)

Каждый сценарий — Markdown-файл с фронтматтером и шагами.  
Агент читает шаги и исполняет их через `bash` + `curl`.

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
curl -s -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{"title":"Test task","description":"E2E test","emailId":"test-001","sender":"test@test.com","priority":"HIGH"}'
```
**Expected:** HTTP 201, тело содержит `"status":"PENDING"`

### Step 2 — Проверить что задача видна в списке pending
```bash
curl -s http://localhost:8082/api/tasks/pending
```
**Expected:** HTTP 200, массив содержит задачу с `emailId = "test-001"`

### Step 3 — Подтвердить задачу
```bash
curl -s -X POST http://localhost:8082/api/tasks/{id}/confirm
```
**Expected:** HTTP 200, `"status":"TODO"`

### Step 4 — Проверить что задача больше не в PENDING
```bash
curl -s http://localhost:8082/api/tasks/pending
```
**Expected:** массив не содержит `emailId = "test-001"`

## Cleanup
```bash
# Удалить тестовые данные
curl -s -X POST http://localhost:8082/api/tasks/{id}/reject
```
```

---

## 4. AGENT.md — инструкции агента

```markdown
# LeaderOS Test Runner Agent

## Роль
Ты — автоматический тест-инженер LeaderOS.
Запускаешься из корня `Leader-Role-Framework/`.
Цель: прогнать все E2E сценарии и составить отчёт.

## Алгоритм запуска

1. **Инфраструктура**
   - `docker compose up -d` из корня
   - Дождаться health: postgres (:5432), opensearch (:9200), maildev (:1080)
   - Таймаут 60 секунд, проверять каждые 5 сек

2. **Сборка сервисов** (параллельно)
   - `cd JavaMemoryService && mvn package -q -DskipTests`
   - `cd JavaMailAgent && mvn package -q -DskipTests`
   - `cd JavaRagService && mvn package -q -DskipTests`

3. **Запуск сервисов** (в порядке зависимостей)
   - JavaMemoryService :8082 → ждать /actuator/health = UP
   - JavaRagService :8081 → ждать /actuator/health = UP (или /mcp/rag_status)
   - JavaMailAgent :8080 → ждать /actuator/health = UP

4. **Сбор сценариев**
   - Найти все `*/test_e2e/*.md`
   - Найти все `e2e-integration/*.md`
   - Отсортировать по имени файла (числовой префикс)

5. **Исполнение сценариев**
   - Для каждого сценария: читать шаги, выполнять bash/curl
   - Сравнивать результат с Expected
   - Фиксировать: PASS / FAIL + фактический ответ

6. **Cleanup**
   - Выполнить секцию Cleanup каждого сценария
   - Остановить Java-процессы
   - `docker compose down` (опционально, по флагу)

7. **Отчёт**
   - Сохранить в `test-runner/reports/TEST-REPORT-{date}.md`
   - Вывести summary в консоль

## Правила

- При BUILD FAILURE сервиса — пропустить его сценарии, зафиксировать BUILD_FAILED
- При STARTUP FAILURE — пропустить сценарии, зафиксировать STARTUP_FAILED  
- Таймаут одного шага/scenario зависит от сценария; для scheduler/index flows допустимы ожидания 90-120 секунд
- Не останавливаться при FAIL — прогонять все сценарии до конца
- Переменные из шагов ({id} и т.п.) — извлекать из предыдущего ответа через jq
```

---

## 5. Формат отчёта

```markdown
# TEST-REPORT-2026-06-11

**Запуск:** 2026-06-11 14:32:00  
**Окружение:** local / ollama  
**Профиль:** определяется сценарием (`local`, `local,e2e`, `ollama`)

---

## Summary

| Сервис | Сборка | Запуск | Сценариев | PASS | FAIL | SKIP |
|--------|--------|--------|-----------|------|------|------|
| JavaMemoryService | ✅ | ✅ | 3 | 2 | 1 | 0 |
| JavaMailAgent | ✅ | ✅ | 3 | 3 | 0 | 0 |
| JavaRagService | ❌ BUILD_FAILED | — | 3 | 0 | 0 | 3 |
| **Итого** | | | **9** | **5** | **1** | **3** |

---

## JavaMemoryService

### ✅ 01_mcp_tools_list — PASS (1.2s)
### ✅ 02_pending_task_flow — PASS (2.1s)

### ❌ 03_ui_today — FAIL

**Шаг:** Step 2 — GET /ui/today  
**Expected:** HTTP 200, содержит `"Ожидают подтверждения"`  
**Actual:** HTTP 200, тело не содержит ожидаемую строку  
**Response body:**
```
<html>...<div class="today-section">...</div>...</html>
```
**Вероятная причина:** секция pending tasks не рендерится если список пуст

---

## JavaMailAgent

### ✅ 01_maildev_connection — PASS
### ✅ 02_mail_poll_cycle — PASS  
### ✅ 03_agent_classification — PASS

---

## JavaRagService

**Статус: BUILD_FAILED**  
**Ошибка:**
```
[ERROR] Failed to execute goal ... spring-ai-bom:1.0.0 not found
```
**Все 3 сценария: SKIP**

---

## Рекомендации к BUGFIX_CR

1. **CR-MEM-BUGFIX-001** — ui_today: секция pending не отображается при пустом списке  
   Файл: `JavaMemoryService/src/main/resources/templates/today.html`  
   Условие th:if не срабатывает

2. **CR-RAG-BUGFIX-001** — BUILD_FAILED: spring-ai-bom версия  
   Файл: `JavaRagService/pom.xml`  
   Попробовать версию 1.0.0-M6 или 1.0.0-RC1
```

---

## 6. Точка входа — run-tests.sh

```bash
#!/usr/bin/env bash
# LeaderOS Test Runner
# Запуск: ./test-runner/run-tests.sh [--no-docker-down] [--service JavaMemoryService]

set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

DATE=$(date +%Y-%m-%d)
REPORT_DIR="test-runner/reports"
REPORT="$REPORT_DIR/TEST-REPORT-$DATE.md"

echo "🚀 LeaderOS Test Runner — $DATE"
echo "Root: $ROOT_DIR"

# Запустить агента
claude --print "
Ты — LeaderOS Test Runner.
Корень проекта: $ROOT_DIR
Прочитай AGENT.md из test-runner/AGENT.md и следуй инструкциям.
Сохрани отчёт в: $REPORT
"
```

---

## 7. BUGFIX_CR workflow

После получения отчёта:

```
1. Прочитать секцию "Рекомендации к BUGFIX_CR" в отчёте
2. Для каждого бага создать файл:
   {Service}/cr/CR-{NNN}-bugfix-{name}.md
3. Передать CR в Claude Code для исправления
4. Перезапустить test-runner для валидации фикса
```

**Формат CR для бага:**

```markdown
# CR-MEM-BUGFIX-001: ui_today pending section empty state

**Дата:** 2026-06-11
**Статус:** Draft
**Тип:** bugfix
**Источник:** TEST-REPORT-2026-06-11 / 03_ui_today / Step 2

## Проблема
Секция "Ожидают подтверждения" не рендерится когда список PENDING задач пуст.
Expected: секция скрыта (`th:if` работает), но при наличии задач — видна.
Actual: секция отсутствует даже после создания PENDING задачи.

## Гипотеза
th:if проверяет переменную до загрузки данных / неверное имя переменной в модели.

## Файл для исправления
`JavaMemoryService/src/main/resources/templates/today.html`

## Как воспроизвести
Прогнать сценарий: `JavaMemoryService/test_e2e/03_ui_today.md`
```

---

## 8. Порядок реализации

1. Создать `test-runner/AGENT.md` (инструкции агента — из секции 4)
2. Создать `test-runner/run-tests.sh` (точка входа)
3. Создать первые E2E сценарии для `JavaMemoryService/test_e2e/`:
   - `01_health_check.md` — самый простой, actuator/health
   - `02_pending_task_flow.md` — полный цикл PENDING → TODO
   - `03_mcp_tools_list.md` — MCP handshake
4. Прогнать → получить первый отчёт
5. По отчёту создать BUGFIX_CR для каждого FAIL
6. Повторять до тех пор, пока все сценарии PASS = MVP готов

---

## 9. Зависимости

- `jq` — для извлечения id из JSON-ответов
- `curl` — HTTP запросы
- `docker compose` v2
- `mvn` в PATH
- `claude` CLI в PATH (для запуска агента)
