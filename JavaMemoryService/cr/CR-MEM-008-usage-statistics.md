# CR-MEM-003: Usage Statistics UI и сбор событий использования LeaderOS

**Дата:** 2026-06-14  
**Статус:** Draft  
**Сервис:** MEM  
**Зависимости:** `CR-ARCH-002-memory-as-agent-core.md`  
**Выполнять после:** внедрения Memory Service как единой точки входа AI-агента

---

## 1. Проблема / мотивация

Для презентации через неделю нужно показать не только работающий UI, но и измеримую пользу LeaderOS:

- сколько информации обработано;
- сколько вопросов задано агенту;
- сколько ответов найдено;
- сколько задач создано автоматически;
- сколько времени сэкономлено.

Сейчас эти цифры не собираются централизованно. После `CR-ARCH-002` все основные AI-запросы должны проходить через `JavaMemoryService`, поэтому именно Memory Service должен стать владельцем usage statistics.

---

## 2. Целевое решение

Добавить в `JavaMemoryService`:

1. таблицу `memory.usage_events`;
2. service-layer для записи usage events;
3. REST endpoint для чтения агрегированной статистики;
4. Thymeleaf страницу `/ui/stats`;
5. пункт навигации `Статистика`;
6. автоматическую запись событий из ключевых сценариев:
   - вопросы агенту;
   - поиск знаний через RAG;
   - создание задач агентами;
   - capture notes;
   - обработка capture;
   - завершение задач.

---

## 3. Страница UI

Добавить страницу:

```text
GET /ui/stats
```

Добавить пункт в общую навигацию Memory Service:

```text
Today | Notes | Incidents | Risks | People | Stats
```

Если в шаблонах навигация дублируется, вынести её в общий Thymeleaf fragment только если это не раздувает CR. Иначе аккуратно добавить ссылку во все текущие страницы.

---

## 4. Что показывать на странице

### 4.1. Переключатель периода

Поддержать периоды:

```text
Today
7 days
30 days
All time
```

URL:

```http
GET /ui/stats?period=today
GET /ui/stats?period=7d
GET /ui/stats?period=30d
GET /ui/stats?period=all
```

Default:

```text
7d
```

### 4.2. Главные карточки

Показать карточки:

```text
Questions asked
Successful answers
Success rate
RAG searches
Tasks created by agents
Captures processed
Saved time
```

Пример:

```text
46 questions
41 successful answers
89% success rate
132 RAG searches
24 agent-created tasks
87 captures processed
11.5 hours saved
```

### 4.3. Таблица последних событий

Показать последние 50 событий:

```text
Time | Event type | Source | Status | Saved minutes | Details
```

### 4.4. Блок "Sources used"

MVP-агрегация по `source`:

```text
memory
rag
mail-agent
capture-bot
agent
```

### 4.5. Блок "Saved time formula"

Показать формулу прямо в UI, чтобы на демо было прозрачно:

```text
ASK_ANSWERED = 15 min
RAG_RESULT_USED = 10 min
MAIL_TASK_CREATED = 3 min
CAPTURE_PROCESSED = 2 min
```

---

## 5. Модель данных

Добавить Flyway migration в `JavaMemoryService`:

```text
V{next}__usage_events.sql
```

Таблица:

```sql
CREATE TABLE memory.usage_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(128),
    entity_type VARCHAR(64),
    entity_id VARCHAR(128),
    duration_ms BIGINT,
    saved_minutes INTEGER NOT NULL DEFAULT 0,
    metadata_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_events_created_at
    ON memory.usage_events (created_at);

CREATE INDEX idx_usage_events_event_type_created_at
    ON memory.usage_events (event_type, created_at);

CREATE INDEX idx_usage_events_source_created_at
    ON memory.usage_events (source, created_at);

CREATE INDEX idx_usage_events_correlation_id
    ON memory.usage_events (correlation_id);
```

---

## 6. Event types

Добавить enum:

```java
public enum UsageEventType {
    ASK_QUESTION,
    ASK_ANSWERED,
    ASK_FAILED,

    KNOWLEDGE_SEARCH,
    RAG_SEARCH,
    RAG_RESULT_USED,

    TASK_CREATED,
    TASK_COMPLETED,
    MAIL_TASK_CREATED,

    CAPTURE_CREATED,
    CAPTURE_PROCESSED,

    NOTE_CREATED,
    RISK_CREATED,
    INCIDENT_CREATED
}
```

MVP обязательные:

- `KNOWLEDGE_SEARCH`
- `RAG_SEARCH`
- `TASK_CREATED`
- `MAIL_TASK_CREATED`
- `CAPTURE_CREATED`
- `CAPTURE_PROCESSED`
- `TASK_COMPLETED`

`ASK_QUESTION` / `ASK_ANSWERED` можно писать сразу, если в CR-ARCH-002 появится `/api/ask` или аналогичный сценарий. Если `/api/ask` ещё нет, оставить enum и тестовые/ручные API для будущего.

---

## 7. Sources

Использовать строковое поле `source`.

Рекомендуемые значения:

```text
memory-service
mail-agent
capture-bot
ai-agent
rag-service
manual-ui
test
```

Важно: внешние сервисы не пишут напрямую в БД Memory. Они вызывают API Memory, а Memory Service сам пишет usage events.

---

## 8. Saved time formula

MVP-формула:

```text
ASK_ANSWERED       = 15 minutes
RAG_RESULT_USED    = 10 minutes
MAIL_TASK_CREATED  = 3 minutes
CAPTURE_PROCESSED  = 2 minutes
TASK_CREATED       = 1 minute
```

Реализация:

- захардкодить в `UsageSavedTimePolicy`;
- в будущем вынести в настройки.

Если событие передаёт `saved_minutes` явно — использовать его.  
Если нет — брать default из policy.  
Если event type неизвестен — `0`.

---

## 9. Backend implementation

### 9.1. Entity / Repository

Добавить:

```java
UsageEvent
UsageEventRepository
UsageEventService
UsageStatsService
UsageSavedTimePolicy
```

### 9.2. Service API

```java
void record(UsageEventCommand command);

UsageStats getStats(UsageStatsPeriod period);

List<UsageEventDto> getRecentEvents(UsageStatsPeriod period, int limit);
```

### 9.3. REST API

Добавить endpoint:

```http
GET /api/stats/usage?period=7d
```

Response:

```json
{
  "period": "7d",
  "questionsAsked": 46,
  "successfulAnswers": 41,
  "successRate": 89.1,
  "ragSearches": 132,
  "agentCreatedTasks": 24,
  "capturesProcessed": 87,
  "savedMinutes": 690,
  "savedHours": 11.5,
  "eventsBySource": {
    "memory-service": 10,
    "mail-agent": 24,
    "capture-bot": 87,
    "rag-service": 132
  }
}
```

### 9.4. Optional debug endpoint

Для e2e и ручной проверки добавить dev/local endpoint:

```http
POST /api/stats/events
```

Request:

```json
{
  "eventType": "RAG_SEARCH",
  "source": "test",
  "status": "SUCCESS",
  "savedMinutes": 10,
  "metadata": {
    "query": "e2e stats"
  }
}
```

Важно: если endpoint остаётся в prod, он должен быть явно безопасным и не позволять подменять критичные данные. Для MVP можно включить только в `local` profile.

---

## 10. Где писать события

### 10.1. `POST /api/tasks`

При создании задачи:

```text
TASK_CREATED
source = manual-ui или memory-service
```

Если есть признак агентского создания — source `ai-agent`.

### 10.2. `POST /api/tasks/pending`

Так как Mail Agent создаёт pending task через Memory Service, здесь писать:

```text
MAIL_TASK_CREATED
TASK_CREATED
```

source:

```text
mail-agent
```

Если source неизвестен — `memory-service`.

### 10.3. `PATCH /api/tasks/{id}/status`

При переходе в `DONE` писать:

```text
TASK_COMPLETED
```

### 10.4. `POST /api/capture`

Писать:

```text
CAPTURE_CREATED
```

source брать из request, например:

```text
agent
manual-ui
```

### 10.5. Capture processing

После успешной классификации capture:

```text
CAPTURE_PROCESSED
```

source:

```text
capture-bot
```

metadata:

```json
{
  "classification": "TASK|RISK|NOTE|QUESTION|PERSON_NOTE|KNOWLEDGE|JOURNAL"
}
```

### 10.6. `POST /api/knowledge/search`

После `CR-ARCH-002` писать:

```text
KNOWLEDGE_SEARCH
RAG_SEARCH
```

Если результат RAG не пустой:

```text
RAG_RESULT_USED
```

metadata:

```json
{
  "query": "...",
  "topK": 5,
  "resultsCount": 3
}
```

---

## 11. UI Acceptance Criteria

Страница `/ui/stats` должна:

- [ ] открываться без ошибок;
- [ ] быть доступна из навигации;
- [ ] показывать период `7d` по умолчанию;
- [ ] поддерживать `today`, `7d`, `30d`, `all`;
- [ ] показывать saved time в минутах и часах;
- [ ] показывать success rate;
- [ ] показывать последние события;
- [ ] корректно отображать пустое состояние, если событий нет.

---

## 12. E2E тесты

Добавить сценарии:

```text
JavaMemoryService/test_e2e/13_usage_stats_events.md
JavaMemoryService/test_e2e/14_usage_stats_ui.md
```

### 12.1. `13_usage_stats_events.md`

Проверяет:

1. Создать несколько usage events через local/debug API или через реальные endpoint-ы.
2. Вызвать:

```bash
curl -s "$MS_URL/api/stats/usage?period=7d" | jq
```

3. Проверить:
   - `ragSearches >= 1`;
   - `savedMinutes > 0`;
   - `eventsBySource` содержит `test` или `memory-service`.

### 12.2. `14_usage_stats_ui.md`

Проверяет:

```bash
curl -s "$MS_URL/ui/stats?period=7d"
```

Expected:

- HTTP 200;
- body содержит `Statistics` или `Статистика`;
- body содержит `Saved time` или `Сэкономлено`;
- body содержит переключатели периодов.

### 12.3. Обновить test-runner

Добавить новые сценарии в список MemoryService e2e.

---

## 13. Изменения в документации

Обновить:

### `JavaMemoryService/RFC/RFC-memory-service.md`

Добавить раздел:

```markdown
## Usage Statistics

Memory Service owns usage statistics. It records usage_events for AI-agent flows, knowledge search, task creation, capture processing and task completion.
```

### `ARCHITECTURE.md`

Добавить в Memory Service:

- `Usage Statistics`;
- `/ui/stats`;
- `memory.usage_events`;
- связь с `CR-ARCH-002`.

### `README.md`

Добавить в список функций:

```markdown
- Usage Statistics UI: /ui/stats — показывает вопросы агенту, RAG search, задачи, captures и оценку сэкономленного времени.
```

---

## 14. Acceptance Criteria

CR считается выполненным, если:

- [ ] Добавлена таблица `memory.usage_events`.
- [ ] Добавлены индексы для периода, типа события и source.
- [ ] Добавлен `UsageEventService`.
- [ ] Добавлен `UsageStatsService`.
- [ ] Добавлен REST endpoint `GET /api/stats/usage`.
- [ ] Добавлена UI страница `/ui/stats`.
- [ ] Добавлена ссылка в навигацию.
- [ ] События пишутся из task, pending task, capture, capture processing, knowledge search.
- [ ] Saved time считается по MVP-формуле.
- [ ] Пустое состояние UI корректно отображается.
- [ ] Добавлены e2e сценарии `13_usage_stats_events.md` и `14_usage_stats_ui.md`.
- [ ] Обновлены RFC и master spec.
- [ ] Все MemoryService critical/high e2e тесты проходят.

---

## 15. Как тестировать

```bash
docker compose up -d
./test-runner/build.sh
./test-runner/start-services.sh --profile local
./test-runner/healthcheck.sh
```

Проверить API:

```bash
curl -s "http://localhost:8082/api/stats/usage?period=7d" | jq
```

Проверить UI:

```bash
curl -s "http://localhost:8082/ui/stats?period=7d" | head
```

Открыть в браузере:

```text
http://localhost:8082/ui/stats
```

E2E:

```bash
# Прогнать:
# JavaMemoryService/test_e2e/13_usage_stats_events.md
# JavaMemoryService/test_e2e/14_usage_stats_ui.md
```

---

## 16. Важные ограничения

- Не давать JavaRagService прямой доступ к БД Memory.
- Не писать usage events из других сервисов напрямую в PostgreSQL.
- Не строить Grafana в рамках этого CR.
- Не усложнять saved time ML/эвристиками — только простая MVP-формула.
- Не блокировать основной user flow, если запись usage event упала.
- Ошибка записи статистики должна логироваться, но не ломать создание задач / capture / search.
