# CR-BUGFIX-001: Исправить регрессии E2E после прогона 2026-06-12

**Дата:** 2026-06-12  
**Статус:** Draft  
**Тип:** BugFix  
**Сервис:** JavaMailAgent + JavaMemoryService + test-runner  
**Источник:** `test-runner/reports/TEST-REPORT-2026-06-12.md`  
**Важно:** этот CR только описывает исправления. Не применять во время параллельных тестов.

---

## Проблема / Мотивация

Прогон E2E на профиле `local` показал:

- JavaRagService стабилен: 7/7 PASS.
- JavaMemoryService имеет локальные проблемы в MCP/capture сценариях.
- JavaMailAgent и сквозные `e2e-integration` имеют системные падения email → memory flow.
- Все `e2e-integration` сценарии не прошли по фактическим Expected, даже если часть shell-блоков завершалась с exit code 0.

Ключевой повторяющийся дефект: email REQUEST обрабатывается MailAgent, но созданная PENDING-задача в MemoryService не сохраняет `sender`. Из-за этого сценарии ищут задачу по отправителю, получают `TASK_ID=null` и дальше падают на confirm/reject/done с HTTP 400.

---

## Scope

Исправить только дефекты, подтвержденные отчетом `TEST-REPORT-2026-06-12.md`.

В scope:
- контракт MailAgent → MemoryService для pending task;
- mock-классификация email в `local`;
- mark-as-read для NOISE в Maildev;
- capture routing для PERSON_NOTE / KNOWLEDGE / email CAPTURE;
- MCP E2E сценарии и тестовые env endpoints.

Не в scope:
- JavaRagService core logic;
- новые фичи UI;
- рефакторинг вне затронутых сценариев;
- запуск с профилем `local,e2e`, если явно не решено изменить требование тестов.

---

## Баги к исправлению

### BUG-001: REQUEST email создает PENDING task без sender

**Симптомы:**
- `JavaMailAgent/test_e2e/07_integration_memory_service.md`: PENDING count вырос, но задача не находится по `sender=product@company.ru`.
- `e2e-integration/01_email_to_pending_task.md`: `TASK_ID=null`, confirm/done → HTTP 400.
- `e2e-integration/03_pending_confirm_reject.md`: `Confirm task: null`, `Reject task: null`.
- В `/api/tasks/pending` email-задачи имеют `sender:null`.

**Ожидаемое поведение:**
- MailAgent передает `sender` в payload `POST /api/tasks/pending`.
- MemoryService сохраняет `sender`.
- `/api/tasks/pending` возвращает `sender` в DTO.

**Проверить:**
```bash
curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.sender == "product@company.ru")] | length'
```
Должно быть `>= 1` после REQUEST письма.

---

### BUG-002: NOISE письмо не становится read в Maildev API

**Симптомы:**
- MailAgent логирует `Email ... marked as read (NOISE)`.
- Maildev API продолжает возвращать `read=false` / `unread=1`.
- Падают `JavaMailAgent/test_e2e/03_poll_cycle_noise.md` и `e2e-integration/02_noise_no_task_created.md`.

**Ожидаемое поведение:**
- После классификации `NOISE` письмо помечается read в Maildev.
- Повторный `GET $MAILDEV_URL/email` показывает `read=true`.

**Проверить:**
```bash
curl -s "$MAILDEV_URL/email" \
  | jq '[.[] | select(.from[0].address == "ci@jenkins.local" and .read == false)] | length'
```
Должно быть `0`.

---

### BUG-003: Mock email classifier не соответствует E2E контракту

**Симптомы:**
- Письмо с `ОТВЕТН` / reply классифицируется как `REQUEST`, а не `DRAFT`.
- Дедлайн-сценарий получает `[P2]`, хотя Expected требует HIGH → `P1`.
- Mixed batch создает 2 REQUEST вместо ожидаемого одного REQUEST и одного DRAFT.

**Ожидаемое поведение local/mock:**
- `BUILD` / `passed` / `Duration` → `NOISE`.
- `ОТВЕТН` / `ответ` / `черновик` / reply intent → `DRAFT`.
- обычная просьба / задача → `REQUEST`.
- дедлайн / срочно / важно → `HIGH`, в `plans/today.md` отображается как `P1`.

**Проверить:**
- `JavaMailAgent/test_e2e/04_poll_cycle_request.md`
- `JavaMailAgent/test_e2e/06_multiple_emails.md`
- `e2e-integration/04_mixed_batch_three_types.md`

---

### BUG-004: Capture routing неполный

**Симптомы:**
- `e2e-integration/07_capture_all_types.md`:
  - `PERSON_NOTE route FAIL`;
  - `KNOWLEDGE route FAIL`.
- `e2e-integration/06_capture_knowledge_to_rag.md`:
  - `KNOWLEDGE_FILE` пустой;
  - search нашел старый source, не текущий `RUN_ID`.
- `e2e-integration/08_email_capture_type.md`:
  - `New captures: 0`;
  - `Processed twice (count=2)`.

**Ожидаемое поведение:**
- `PERSON_NOTE` создает/находит person и добавляет note, доступную через `/api/people/name/{name}/notes`.
- `KNOWLEDGE` создает файл в `rag-inbox/captures/` с текущим `RUN_ID`.
- RagService индексирует именно новый файл.
- Email CAPTURE не обрабатывается повторно.

**Проверить:**
- `e2e-integration/06_capture_knowledge_to_rag.md`
- `e2e-integration/07_capture_all_types.md`
- `e2e-integration/08_email_capture_type.md`

---

### BUG-005: MCP сценарии не соответствуют текущему transport contract

**Симптомы:**
- `JavaMemoryService/test_e2e/04_context_no_pending.md` вызывает `/mcp/message` без `sessionId` и получает `Session ID missing in message endpoint`.
- `JavaMemoryService/test_e2e/09_mcp_tools.md` получает SSE session, но `tools/list` не возвращает ожидаемые tool names.

**Ожидаемое поведение:**
- Все MCP E2E сценарии используют единый SSE flow:
  1. открыть `/mcp/sse`;
  2. извлечь `sessionId`;
  3. отправлять JSON-RPC в `/mcp/message?sessionId=...`;
  4. читать ответы из SSE stream.
- `tools/list` возвращает ожидаемые tools:
  - `getContext`
  - `getTasks`
  - `createTask`
  - `markTaskDone`
  - `createIncident`
  - `addRisk`
  - `addPeopleNote`

---

### BUG-006: E2E сценарии используют устаревшие endpoints и хрупкий jq quoting

**Симптомы:**
- Часть JavaMailAgent сценариев hardcode-ит:
  - `http://localhost:1080`
  - `smtp://localhost:1025`
- В текущем `local` окружении рабочие endpoints:
  - `MAILDEV_URL=http://172.80.2.1:18080`
  - `MAILDEV_SMTP=172.80.2.1:1025`
- В Memory capture сценариях jq ломается на строках с пробелами:
  - `jq: syntax error ... shell quoting issues`

**Ожидаемое поведение:**
- Все сценарии используют `env.sh`, а не hardcoded endpoints.
- jq-фильтры передают значения через `--arg` / `--argjson`, а не через string interpolation shell.
- Проверки Expected должны завершаться ненулевым кодом при несовпадении, чтобы runner не считал `RAN` за PASS.

---

## Acceptance Criteria

### AC-1: Сборка

```bash
./test-runner/build.sh
```

Ожидаемо:
- JavaMemoryService build OK
- JavaMailAgent build OK
- JavaRagService build OK

Если проблема `ru.andreyz:common:1.0.0` / `nexus.gigachat.ru handshake_failure` остается внешней, зафиксировать отдельный infra CR и использовать локальный `mvn install` common как временное решение.

### AC-2: Запуск local

```bash
./test-runner/start-services.sh --profile local
./test-runner/healthcheck.sh
```

Ожидаемо:
- все Docker зависимости OK;
- все Java services OK;
- UI endpoints OK.

### AC-3: Модульные E2E

```bash
# по инструкции test-runner/AGENT.md
JavaMemoryService/test_e2e/*.md
JavaMailAgent/test_e2e/*.md
JavaRagService/test_e2e/*.md
```

Ожидаемо:
- JavaRagService остается 7/7 PASS;
- JavaMailAgent: все сценарии PASS;
- JavaMemoryService: MCP и capture сценарии PASS или явно SKIP только если требуют профиль не `local`.

### AC-4: Сквозные E2E

```bash
source e2e-integration/env.sh
e2e-integration/*.md
```

Ожидаемо:
- `01_email_to_pending_task` PASS;
- `02_noise_no_task_created` PASS;
- `03_pending_confirm_reject` PASS;
- `04_mixed_batch_three_types` PASS;
- `05_full_daily_cycle` PASS;
- `06_capture_knowledge_to_rag` PASS;
- `07_capture_all_types` PASS;
- `08_email_capture_type` PASS.

---

## Рекомендованный порядок работ

1. Починить build/dependency resolution или локальную установку `common`.
2. Исправить контракт `sender` MailAgent → MemoryService.
3. Исправить mock email classifier: NOISE / REQUEST / DRAFT / HIGH priority.
4. Исправить Maildev mark-as-read для NOISE.
5. Исправить capture routing PERSON_NOTE / KNOWLEDGE / email CAPTURE dedup.
6. Обновить E2E сценарии: env endpoints, jq quoting, MCP SSE flow, строгие asserts.
7. Повторить полный прогон и создать новый `TEST-REPORT`.

---

## Риски

- Исправление mock classifier может изменить ожидаемые результаты старых сценариев, которые полагались на фиксированный mock response.
- Изменение DTO pending task может потребовать миграцию/обновление API contracts.
- Mark-as-read зависит от Maildev API semantics; нужно проверять фактический endpoint, а не только лог MailAgent.
- Capture routing может затронуть файловую структуру `rag-inbox/` и `workspace/08_daily_journal`.

---

## Definition of Done

- Все исправления покрыты соответствующими E2E markdown-сценариями.
- Новый отчет в `test-runner/reports/TEST-REPORT-{date}.md`.
- В отчете нет `TASK_ID=null`, HTTP 400 на status transition, `unread=1` для NOISE, `PERSON_NOTE route FAIL`, `KNOWLEDGE route FAIL`.
- `e2e-integration` проходит 8/8 PASS на профиле `local`.
