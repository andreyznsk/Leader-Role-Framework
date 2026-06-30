# 2026-06-28_CR-MEM-012: CaptureBot UI

**Дата:** 2026-06-28  
**Статус:** Implemented  
**Сервис:** MEM / JavaMemoryService  
**Ветка:** feature/mailAg-001  
**Связанный Issue:** TBD

## Проблема / Мотивация

В презентации LeaderOS CaptureBot является отдельным пользовательским сценарием: техлид быстро фиксирует мысль, система сохраняет raw capture без интерпретации, а вечером бот классифицирует записи и раскладывает их по задачам, заметкам, рискам, знаниям, людям или журналу.

Сейчас backend-flow CaptureBot уже существует, но пользователю не хватает единого UI-контроля:

- быстро добавить raw capture из браузера;
- сразу увидеть, что запись уже попала в inbox;
- отличить необработанные записи от обработанных;
- увидеть ошибки обработки;
- понять, куда именно CaptureBot разложил запись;
- избежать повторного внесения одной и той же мысли.

Главная проблема: CaptureBot работает как фоновый механизм, но не как управляемый пользовательский inbox.

## Решение

Реализовать единую страницу CaptureBot UI:

```text
GET /ui/captures
```

Страница должна совмещать:

1. **Quick Capture input** — поле ввода raw текста.
2. **Capture History** — таблицу истории всех внесённых capture entries.
3. **Status filters** — быстрые кнопки фильтрации по статусам.
4. **Processing visibility** — отображение результата вечерней классификации.

Целевой layout:

```text
┌──────────────────────────────────────────────────────────────┐
│ Filters                                                      │
│ [All] [New] [Processing] [Processed] [Error] [Archived]       │
│ Date | Source | Route | Search                               │
├──────────────────────────────┬───────────────────────────────┤
│ Quick Capture                │ Capture History                │
│                              │                               │
│ [ textarea raw text      ]   │ Status | Created | Text | Route│
│ [ Save / New ]               │ Result | Actions               │
└──────────────────────────────┴───────────────────────────────┘
```

## Ключевой принцип

Создание capture из UI не должно запускать AI-анализ.

Flow создания:

```text
User → /ui/captures textarea
    → POST /api/capture
    → memory.capture_entries(status = NEW)
    → capture-inbox/YYYY-MM-DD/*.md
    → строка сразу видна в Capture History
```

Raw text сохраняется как есть. Классификация происходит отдельно — по расписанию или вручную.

## Capture statuses

Добавить явную state-machine для capture entries:

| Статус | Значение |
|--------|----------|
| `NEW` | Запись добавлена в inbox, но ещё не обработана |
| `PROCESSING` | CaptureBot сейчас обрабатывает запись |
| `PROCESSED` | Запись успешно классифицирована и разложена |
| `ERROR` | Обработка завершилась ошибкой |
| `ARCHIVED` | Запись скрыта/архивирована вручную |

Важно: в UI использовать технически корректное имя `Error`, не `Terror`.

## Capture History table

Минимальные колонки:

| Колонка | Описание |
|---------|----------|
| `Status` | NEW / PROCESSING / PROCESSED / ERROR / ARCHIVED |
| `Created At` | Дата и время создания |
| `Source` | `ui`, `mail`, `agent`, `api` |
| `Text Preview` | Первые 100–150 символов raw текста |
| `Route` | Результат классификации: TASK / RISK / NOTE / NOTICE / KNOWLEDGE / INCIDENT / PERSON_NOTE / JOURNAL |
| `Result` | Ссылка, id или readable reference созданной сущности |
| `Actions` | View / Reprocess / Archive |

## Filters

На странице сверху должны быть быстрые кнопки:

```text
[All] [New] [Processing] [Processed] [Error] [Archived]
```

Дополнительные фильтры:

- date;
- source;
- route;
- search by raw text.

## Изменения в API

Сохранить обратную совместимость текущего endpoint-а:

```http
POST /api/capture
POST /api/capture/process-now
```

Добавить browser-facing API для UI:

```http
GET  /api/captures?status=&date=&source=&route=&q=
GET  /api/captures/{id}
POST /api/captures
POST /api/captures/{id}/process
POST /api/captures/{id}/reprocess
POST /api/captures/{id}/archive
```

Допустимо, чтобы `POST /api/captures` внутри переиспользовал существующий `POST /api/capture` flow.

## Изменения в схеме БД

Добавить или расширить таблицу:

```text
memory.capture_entries
```

Минимальные поля:

```sql
id               BIGSERIAL PRIMARY KEY,
text             TEXT NOT NULL,
source           VARCHAR(64) NOT NULL,
status           VARCHAR(32) NOT NULL,
route            VARCHAR(64),
target_type      VARCHAR(64),
target_id        VARCHAR(128),
target_ref       TEXT,
file_path        TEXT,
error_message    TEXT,
created_at       TIMESTAMP NOT NULL,
updated_at       TIMESTAMP NOT NULL,
processed_at     TIMESTAMP,
archived_at      TIMESTAMP
```

Индексы:

```sql
idx_capture_entries_status_created_at(status, created_at)
idx_capture_entries_created_at(created_at)
idx_capture_entries_route(route)
idx_capture_entries_source(source)
```

## Изменения в CaptureScheduler

Текущую обработку файлов нужно перевести на управляемую обработку через таблицу.

Целевое правило:

```sql
SELECT *
FROM memory.capture_entries
WHERE status = 'NEW'
ORDER BY created_at;
```

Scheduler должен:

1. брать только `NEW` записи;
2. переводить запись в `PROCESSING` перед вызовом AI;
3. после успешной маршрутизации ставить `PROCESSED`;
4. сохранять `route`, `target_type`, `target_id`, `target_ref`, `processed_at`;
5. при ошибке ставить `ERROR` и сохранять `error_message`;
6. не обрабатывать `ARCHIVED` записи;
7. не создавать дубли при повторном запуске.

Файлы `capture-inbox/YYYY-MM-DD/*.md` остаются как raw backup / файловая шина, но управляющая история и источник для UI — таблица `memory.capture_entries`.

## Route values

Поддержать маршруты:

```text
TASK
RISK
NOTE
NOTICE
KNOWLEDGE
INCIDENT
PERSON_NOTE
JOURNAL
QUESTION
UNKNOWN
```

Если текущая реализация не поддерживает `INCIDENT` или `NOTICE` для CaptureBot, добавить как planned route или безопасно маппить в `NOTE`/`KNOWLEDGE` с явным отражением в коде и документации.

## UI actions

### Save / New

Создаёт raw capture со статусом `NEW`.

### View

Открывает полную карточку capture:

- raw text;
- source;
- status;
- route;
- processing result;
- error details.

### Reprocess

Доступно для `ERROR` и `PROCESSED`.

Поведение:

```text
status → NEW
route/result/error fields reset or moved to history if history is implemented
```

### Archive

Переводит запись в `ARCHIVED` без удаления.

## Usage events

Сохранить и расширить текущую метрику использования:

- `CAPTURE_CREATED` — при создании записи;
- `CAPTURE_PROCESSED` — при успешной обработке;
- `CAPTURE_FAILED` — при ошибке обработки, если такой event type уже допустим или может быть добавлен безопасно.

## Изменения в документации

После реализации обновить:

- `ARCHITECTURE.md`;
- `README.md`;
- при необходимости `PRESENTATION.md`, слайд 7, чтобы UI был отражён как часть сценария CaptureBot.

## Как тестировать

### 1. UI smoke

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/ui/captures
```

Expected:

```text
200
```

### 2. Create capture from API

```bash
curl -s -X POST http://localhost:8082/api/captures \
  -H "Content-Type: application/json" \
  -d '{"text":"E2E capture: Иванов хочет перейти в другую команду","source":"ui"}'
```

Expected:

- HTTP 201 or 200;
- response contains `status = NEW`;
- response contains non-empty `id`.

### 3. List NEW captures

```bash
curl -s "http://localhost:8082/api/captures?status=NEW"
```

Expected:

- created capture is present;
- status is `NEW`;
- raw text is unchanged.

### 4. Verify file backup

Expected:

```text
capture-inbox/YYYY-MM-DD/*.md exists
```

The file contains raw text and source metadata.

### 5. Process one capture

```bash
curl -s -X POST http://localhost:8082/api/captures/{id}/process
```

Expected:

- status becomes `PROCESSED` or `ERROR`;
- if `PROCESSED`, route is not empty;
- if `ERROR`, error_message is not empty.

### 6. Reprocess

```bash
curl -s -X POST http://localhost:8082/api/captures/{id}/reprocess
```

Expected:

- record becomes eligible for processing again;
- duplicate target entities are not created silently without explicit logic.

### 7. Archive

```bash
curl -s -X POST http://localhost:8082/api/captures/{id}/archive
```

Expected:

- status becomes `ARCHIVED`;
- scheduler does not process this record.

## Acceptance Criteria

- `/ui/captures` is a single unified CaptureBot page.
- Page contains raw text input for quick capture creation.
- Page contains history table on the same screen.
- Page contains status filter buttons: All, New, Processing, Processed, Error, Archived.
- Creating a capture from UI immediately inserts a row with status `NEW`.
- Creating a capture does not call AI and does not classify text immediately.
- Capture is persisted in `memory.capture_entries`.
- Raw backup file is written to `capture-inbox/YYYY-MM-DD/`.
- Table shows created captures with status, source, text preview and created time.
- Scheduler processes only `NEW` entries.
- Scheduler updates status to `PROCESSING`, then `PROCESSED` or `ERROR`.
- Processed entries show route and result reference.
- Error entries show error message.
- Archived entries are not processed.
- Existing `POST /api/capture` remains backward compatible for MailAgent and external agents.
- E2E scenario is added for CaptureBot UI/API flow.
- Documentation is updated after implementation.

## Зависимости

- JavaMemoryService;
- PostgreSQL schema `memory`;
- existing CaptureScheduler;
- common `AgentClient`;
- existing task/risk/note/people/RAG/journal flows.

## Не входит в scope

- Полноценное редактирование результата классификации через UI;
- сложная история всех reprocess attempts;
- multi-user / multi-tenant access control;
- отдельный UI для RAG index internals;
- интеграция с корпоративным SSO.

---

## Фактическая реализация

**Реализовано:** 2026-06-28 (ветка `feature/mailAg-001`, PR #31, коммит `2d27773`)  
Подтверждено наличие:
- `src/main/resources/templates/captures.html` — CaptureBot UI страница `/ui/captures`
- `test_e2e/tests/capturebot-ui.spec.js` — E2E сценарий

⚠️ Примечание: файл имеет номер CR-MEM-012, который также используется в `2026-06-29_CR-MEM-012-global-search-tsvector-providers.md`. Коллизия номеров требует отдельного шага перенумерации.
