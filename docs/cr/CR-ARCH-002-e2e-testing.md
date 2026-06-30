# CR-ARCH-002: Добавить раздел E2E тестирования в ARCHITECTURE.md

**Дата:** 2026-06-11  
**Статус:** Approved  
**Сервис:** ARCH  
**Зависимости:** test-runner/, JavaMemoryService/test_e2e/, JavaMailAgent/test_e2e/, JavaRagService/test_e2e/, e2e-integration/

---

## Проблема / Мотивация

В ARCHITECTURE.md описаны сервисы, связи, CR workflow и правила коммитов,
но отсутствует описание того как тестировать систему.

В ходе работы над MVP был создан полноценный test-runner суб-агент
с E2E сценариями для каждого сервиса и интеграционными тестами.
Без фиксации этого в главной спеке новый участник не поймёт
как запускать тесты, где лежат сценарии и как читать отчёты.

---

## Решение

Добавить новый раздел **`## E2E Тестирование`** в ARCHITECTURE.md
после раздела `## CR (Change Request) Workflow` и до `## Правила коммитов`.

---

## Изменения в ARCHITECTURE.md

Добавить следующий раздел:

---

## E2E Тестирование

### Философия

Тесты написаны в виде Markdown-сценариев (`test_e2e/*.md`).
Каждый сценарий — последовательность `curl`/`bash` шагов с явным `**Expected:**`.
Claude Code читает сценарии и прогоняет их как агент, сохраняя отчёт.
Разработчик получает отчёт, создаёт `BUGFIX_CR` и повторяет цикл.

```
Сценарий (*.md)
    ↓
Claude Code (агент) прогоняет curl-шаги
    ↓
TEST-REPORT-{date}.md
    ↓
BUGFIX_CR → Claude Code → фикс → следующий прогон
```

---

### Структура test-runner

```
Leader-Role-Framework/
├── test-runner/
│   ├── AGENT.md              ← инструкции для Claude Code агента
│   ├── build.sh              ← сборка всех JAR-ов
│   ├── start-services.sh     ← запуск сервисов (логи → logs/)
│   ├── stop-services.sh      ← остановка сервисов
│   ├── healthcheck.sh        ← проверка всей инфраструктуры
│   └── reports/              ← TEST-REPORT-*.md по каждому прогону
│
├── logs/
│   ├── JavaMemoryService.log
│   ├── JavaMailAgent.log
│   ├── JavaRagService.log
│   ├── build-*.log
│   └── *.pid
│
├── JavaMemoryService/
│   └── test_e2e/             ← юнит E2E сценарии MemoryService
│
├── JavaMailAgent/
│   └── test_e2e/             ← юнит E2E сценарии MailAgent
│
├── JavaRagService/
│   └── test_e2e/             ← юнит E2E сценарии RagService
│
└── e2e-integration/          ← сквозные интеграционные сценарии
```

---

### Запуск инфраструктуры

```bash
# 1. Docker инфраструктура
docker compose up -d

# 2. Сборка JAR-ов
./test-runner/build.sh

# 3. Запуск сервисов
./test-runner/start-services.sh --profile local

# 4. Проверка
./test-runner/healthcheck.sh
```

---

### Запуск тестов (вручную через Claude Code)

```bash
# Юнит E2E — один сервис
"Прочитай test-runner/AGENT.md.
 Прогони сценарии JavaMemoryService/test_e2e/*.
 Сохрани отчёт в test-runner/reports/TEST-REPORT-{дата}-memory.md"

# Интеграционные тесты
"source e2e-integration/env.sh
 Прочитай test-runner/AGENT.md.
 Прогони e2e-integration/01_email_to_pending_task.md
 Сохрани отчёт в test-runner/reports/TEST-REPORT-integration-run1.md"
```

---

### Переменные окружения

Каждый набор сценариев имеет `env.sh` с адресами сервисов.
Загружается перед прогоном: `source {dir}/test_e2e/env.sh`

| Переменная | Назначение | Пример |
|------------|-----------|--------|
| `MAILDEV_URL` | Maildev HTTP API | `http://172.80.2.1:18080` |
| `MAILDEV_SMTP` | Maildev SMTP | `172.80.2.1:1025` |
| `MS_URL` | JavaMemoryService | `http://localhost:8082` |
| `MA_URL` | JavaMailAgent | `http://localhost:8080` |
| `OPENSEARCH_URL` | OpenSearch | `http://localhost:9200` |
| `OLLAMA_URL` | Ollama | `http://localhost:11434` |
| `PGPASSWORD` | PostgreSQL пароль | `mailagent_password` |

---

### Сценарии по сервисам

#### JavaMemoryService (`test_e2e/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_health_check.md` | CRITICAL | actuator/health → UP |
| `02_create_task.md` | HIGH | POST /api/tasks → 201, видна в плане |
| `03_read_daily_plan.md` | HIGH | GET /api/tasks?date= + /api/context + /ui/today |
| `04_edit_task.md` | HIGH | PUT + PATCH /status + file description |
| `05_pending_task_flow.md` | HIGH | PENDING → confirm → TODO / reject → DELETED |
| `06_incidents.md` | HIGH | OPEN → INVESTIGATING → RESOLVED |
| `07_risks.md` | MEDIUM | OPEN → MITIGATED + getContext |
| `08_people_and_notes.md` | MEDIUM | карточка + заметки + поиск |
| `09_mcp_tools.md` | HIGH | SSE flow + tools/list + getTasks |
| `10_task_reorder_and_move.md` | MEDIUM | reorder + move to date |
| `11_ui_smoke.md` | MEDIUM | все /ui/* страницы, H2 console, 404 |

#### JavaMailAgent (`test_e2e/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_health_check.md` | CRITICAL | actuator/health, Maildev, UI /ui/status |
| `02_maildev_send_receive.md` | HIGH | SMTP → Maildev API, read-статус |
| `03_poll_cycle_noise.md` | HIGH | BUILD/passed → NOISE → markAsRead |
| `04_poll_cycle_request.md` | HIGH | дедлайн → REQUEST → today.md + unread |
| `05_deduplication.md` | HIGH | письмо обрабатывается ровно один раз |
| `06_multiple_emails.md` | MEDIUM | 3 типа за один poll + корректные read-статусы |
| `07_integration_memory_service.md` | HIGH | REQUEST → POST /api/tasks/pending → PENDING |

#### JavaRagService (`test_e2e/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_health_check.md` | CRITICAL | сервис, OpenSearch, Ollama, PostgreSQL |
| `02_index_single_document.md` | HIGH | rag_index + idempotency + OpenSearch чанки |
| `03_semantic_search.md` | HIGH | семантический поиск на кириллице |
| `04_scheduler_auto_index.md` | HIGH | file watcher → авто-индексация ≤90 сек |
| `05_index_directory.md` | MEDIUM | rag_index_directory + паттерн *.md |
| `06_reindex_on_change.md` | MEDIUM | старые чанки удаляются, новые приходят |

#### Интеграционные (`e2e-integration/`)

| Файл | Приоритет | Что проверяет |
|------|-----------|--------------|
| `01_email_to_pending_task.md` | CRITICAL | письмо → REQUEST → PENDING в MemoryService |
| `02_pending_confirm_reject.md` | CRITICAL | PENDING → confirm → TODO / reject → DELETED |
| `03_noise_no_task_created.md` | HIGH | NOISE → письмо прочитано, задача НЕ создана |
| `04_draft_no_task_created.md` | HIGH | DRAFT → черновик в drafts/, задача НЕ создана |
| `05_mixed_batch_three_types.md` | HIGH | 3 письма → правильные типы и read-статусы |
| `06_full_daily_cycle.md` | HIGH | письмо → PENDING → TODO → IN_PROGRESS → DONE |

---

### Формат сценария

```markdown
# Scenario: <название>

**service:** <JavaMemoryService | JavaMailAgent | JavaRagService>
**port:** <порт>
**priority:** <CRITICAL | HIGH | MEDIUM | LOW>
**depends_on:** <postgres | maildev | opensearch | ollama>

## Steps

### Step N — <описание>
```bash
<curl или bash команда>
```
**Expected:** <HTTP код, строка в теле, или значение>
**Extract:** `переменная` из ответа → `$VAR`

## Cleanup
```bash
<удаление тестовых данных>
```
```

---

### Формат отчёта

Отчёт сохраняется в `test-runner/reports/TEST-REPORT-{date}-{service}.md`.

```markdown
# TEST-REPORT-2026-06-11-memory

| Сценарий | PASS | FAIL | Примечание |
|----------|------|------|------------|
| 01_health_check | ✅ | — | |

## Обнаруженные дефекты
### CR-MEM-BUGFIX-001 — описание
```

---

### BUGFIX_CR workflow

```
1. TEST-REPORT содержит FAIL
        ↓
2. Создать {Service}/cr/CR-{PREFIX}-BUGFIX-{NNN}-{name}.md
        ↓
3. Передать CR в Claude Code для исправления
        ↓
4. Пересобрать: mvn package -q -DskipTests
        ↓
5. Повторить прогон → новый отчёт
        ↓
6. Повторять пока все сценарии PASS
```

---

### Правила изоляции тестов

- Уникальные маркеры: `E2E:`, `e2e-test-`, `e2e-pending-`
- Cleanup в каждом сценарии
- Count-проверки через `.select(.id == $ID)`, не `length` всего списка
- Тесты устойчивы к повторным прогонам (idempotent)

---

## Также обновить CR-префиксы

Добавить в таблицу:

| `TEST` | test-runner / E2E сценарии |
| `BUGFIX` | исправление бага по результатам тестов (суффикс к PREFIX) |

---

## Коммит после применения

```
ARCH_cr_002 добавлен раздел E2E тестирования
```
