# 2026-06-29_CR-MEM-012: Global Search tsvector для operational providers

**Дата:** 2026-06-29  
**Статус:** Draft  
**Сервис:** MEM  
**Зависимости:** JavaMemoryService, PostgreSQL, существующий Global Search, JavaRagService  
**Не затрагивать:** JavaRagService semantic/vector RAG search

---

## Проблема / Мотивация

Global Search должен искать не только по RAG-документации, но и по operational-слоям LeaderOS: Notice, Tasks, People, Risks, Incidents.

Сейчас для non-RAG providers ожидается простой lexical search: `ILIKE`, exact/partial match или ручная фильтрация по нескольким полям. Такой подход плохо работает для реальных запросов пользователя:

- пользователь пишет естественным языком;
- слова могут быть в разных формах;
- запрос может содержать лишние слова;
- задача/риск/инцидент может называться иначе, чем формулировка пользователя;
- поиск должен быстро работать по всем operational providers параллельно.

Пример:

```text
что зависло по релизу платежей
```

Должен находить задачи, риски, инциденты и notice, где встречаются релевантные слова и формы слов:

```text
релиз, релиза, релизный
платеж, платежи, payment, payments
зависло, блокер, blocked, waiting
```

---

## Решение

Добавить гибкий контекстный полнотекстовый поиск для всех Global Search providers, кроме RAG, на базе PostgreSQL `tsvector` / `tsquery`.

### Основная идея

Для каждой operational entity добавить отдельный `search_vector`:

- `memory.tasks.search_vector`
- `memory.notes.search_vector` или `memory.notices.search_vector` — в зависимости от текущей модели Notice/Note
- `memory.people.search_vector`
- `memory.risks.search_vector`
- `memory.incidents.search_vector`

`search_vector` должен заполняться:

1. для существующих записей — Flyway backfill migration;
2. для новых и изменённых записей — на уровне application service перед сохранением или через PostgreSQL generated column / trigger;
3. при изменении полей, входящих в поисковый контекст.

Global Search request должен обрабатываться так:

```text
user query
  -> SearchQueryParser
      -> normalized query
      -> keywords
      -> tsquery
  -> GlobalSearchService
      -> параллельно запускает non-RAG providers
      -> KnowledgeSearchProvider оставляет старую RAG-логику
  -> providers ищут через PostgreSQL tsvector
  -> merge / score / group by layer
```

---

## Scope

В рамках CR нужно доработать только non-RAG providers:

- `NoticeSearchProvider`
- `TaskSearchProvider`
- `PeopleSearchProvider`
- `RiskSearchProvider`
- `IncidentSearchProvider`

`KnowledgeSearchProvider` не переводить на `tsvector`. Он должен продолжать ходить в `JavaRagService` через существующий knowledge search flow.

---

## Изменения в API

### POST `/api/search`

Текущий контракт сохранить.

Рекомендуемый request:

```json
{
  "query": "что зависло по релизу платежей",
  "layers": ["TASKS", "RISKS", "INCIDENTS", "NOTICE", "PEOPLE", "KNOWLEDGE"],
  "mode": "QUICK",
  "limit": 20
}
```

### Поведение

- Для `TASKS`, `RISKS`, `INCIDENTS`, `NOTICE`, `PEOPLE` использовать PostgreSQL FTS.
- Для `KNOWLEDGE` использовать существующий RAG search.
- Если `layers` не переданы — искать по всем слоям.
- Если `mode = QUICK` — вернуть сгруппированные результаты.
- Если `mode = DEEP` — сначала выполнить поиск, затем передать найденные результаты в `SearchPromptBuilder + AgentClient`.

### Response

Формат результата должен поддерживать score и matched fields:

```json
{
  "query": "что зависло по релизу платежей",
  "mode": "QUICK",
  "results": [
    {
      "layer": "TASKS",
      "id": "123",
      "title": "Подготовить релиз payment-service",
      "snippet": "Blocked: ждём согласование от QA",
      "score": 0.87,
      "source": "memory.tasks",
      "matchedFields": ["title", "description", "status"]
    }
  ]
}
```

---

## Изменения в схеме БД

### 1. Добавить `search_vector`

Пример для задач:

```sql
ALTER TABLE memory.tasks
ADD COLUMN IF NOT EXISTS search_vector tsvector;

CREATE INDEX IF NOT EXISTS idx_tasks_search_vector
ON memory.tasks USING GIN(search_vector);
```

Аналогично для таблиц:

```text
memory.notes / memory.notices
memory.people
memory.risks
memory.incidents
```

Фактические имена таблиц уточнить по текущей схеме проекта.

---

### 2. Backfill существующих записей

Пример для `tasks`:

```sql
UPDATE memory.tasks
SET search_vector =
    setweight(to_tsvector('russian', coalesce(title, '')), 'A') ||
    setweight(to_tsvector('russian', coalesce(description, '')), 'B') ||
    setweight(to_tsvector('russian', coalesce(priority, '')), 'C') ||
    setweight(to_tsvector('russian', coalesce(status, '')), 'C') ||
    setweight(to_tsvector('russian', coalesce(source, '')), 'D');
```

Для каждой сущности использовать свои поля.

---

### 3. Заполнение новых и обновлённых записей

Предпочтительный вариант для MVP — PostgreSQL trigger, чтобы не забыть обновить `search_vector` из Java-кода.

Пример:

```sql
CREATE OR REPLACE FUNCTION memory.update_task_search_vector()
RETURNS trigger AS $$
BEGIN
  NEW.search_vector :=
      setweight(to_tsvector('russian', coalesce(NEW.title, '')), 'A') ||
      setweight(to_tsvector('russian', coalesce(NEW.description, '')), 'B') ||
      setweight(to_tsvector('russian', coalesce(NEW.priority, '')), 'C') ||
      setweight(to_tsvector('russian', coalesce(NEW.status, '')), 'C') ||
      setweight(to_tsvector('russian', coalesce(NEW.source, '')), 'D');
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_tasks_search_vector ON memory.tasks;

CREATE TRIGGER trg_tasks_search_vector
BEFORE INSERT OR UPDATE OF title, description, priority, status, source
ON memory.tasks
FOR EACH ROW
EXECUTE FUNCTION memory.update_task_search_vector();
```

Аналогично создать функции и triggers для:

- notes/notices;
- people;
- risks;
- incidents.

---

## Search Query Parser

Добавить компонент:

```text
SearchQueryParser
```

Ответственность:

1. принять пользовательский запрос;
2. нормализовать строку;
3. разбить на keywords;
4. убрать мусорные стоп-слова;
5. построить `tsquery`;
6. сохранить original query для UI/DEEP prompt.

### Пример DTO

```java
public record ParsedSearchQuery(
    String originalQuery,
    String normalizedQuery,
    List<String> keywords,
    String postgresTsQuery
) {}
```

### Пример поведения

Input:

```text
что зависло по релизу платежей
```

Output:

```json
{
  "originalQuery": "что зависло по релизу платежей",
  "normalizedQuery": "зависло релиз платежи",
  "keywords": ["зависло", "релиз", "платежи"],
  "postgresTsQuery": "зависло:* & релиз:* & платежи:*"
}
```

Для MVP допустимо использовать `websearch_to_tsquery('russian', :query)` или `plainto_tsquery('russian', :normalizedQuery)`.

Рекомендуемый безопасный вариант:

```sql
websearch_to_tsquery('russian', :query)
```

Для prefix matching можно использовать `to_tsquery`, но только после безопасной очистки keywords.

---

## Изменения в провайдерах

### Общий интерфейс

Если интерфейс ещё не введён, добавить:

```java
public interface SearchProvider {
    SearchLayer layer();
    List<SearchResult> search(ParsedSearchQuery query, SearchOptions options);
}
```

### TaskSearchProvider

Искать по `memory.tasks.search_vector`:

```sql
SELECT
    id,
    title,
    description,
    status,
    priority,
    ts_rank_cd(search_vector, websearch_to_tsquery('russian', :query)) AS rank
FROM memory.tasks
WHERE search_vector @@ websearch_to_tsquery('russian', :query)
  AND status <> 'DELETED'
ORDER BY rank DESC, updated_at DESC
LIMIT :limit;
```

Добавить domain boost:

```text
OPEN / TODO / IN_PROGRESS > DONE
HIGH priority > MEDIUM > LOW
overdue tasks выше
recent updated выше
```

---

### PeopleSearchProvider

Поля для search vector:

```text
name
role
team
notes
tags
source
```

Domain boost:

```text
name match выше notes match
active people выше archived/deleted
recent notes выше старых
```

---

### RiskSearchProvider

Поля:

```text
title
description
impact
mitigation
status
severity
source
```

Domain boost:

```text
OPEN risks выше MITIGATED/CLOSED
HIGH severity выше MEDIUM/LOW
```

---

### IncidentSearchProvider

Поля:

```text
title
description
root_cause
action_items
status
severity
source
```

Domain boost:

```text
OPEN / INVESTIGATING выше RESOLVED
P1/P2 выше P3
recent incidents выше старых
```

---

### NoticeSearchProvider

Поля зависят от текущей модели Notice/Note.

Если Notice хранится как markdown-файл, нужно принять одно из решений:

1. либо импортировать Notice в таблицу `memory.notices`;
2. либо использовать существующую `memory.notes`, если NOTICE уже представлен как operational note;
3. либо создать отдельную таблицу `memory.notices`.

Поля:

```text
title
text
tags
source
created_at
```

Domain boost:

```text
recent notices выше старых
source=email/capture можно показывать в UI
```

---

## Параллельный поиск

`GlobalSearchService` должен запускать providers параллельно.

Пример логики:

```java
List<CompletableFuture<List<SearchResult>>> futures = selectedProviders.stream()
    .map(provider -> CompletableFuture.supplyAsync(
        () -> provider.search(parsedQuery, options),
        searchExecutor
    ))
    .toList();
```

Требования:

- ошибка одного provider не должна ломать весь поиск;
- ошибка должна попадать в `SearchDiagnostics`;
- `KnowledgeSearchProvider` может быть медленнее, но не должен блокировать QUICK по operational providers дольше общего timeout;
- для QUICK режима выставить общий timeout, например 1–2 секунды;
- для DEEP режима можно позволить больший timeout.

---

## Scoring

Итоговый score provider должен быть комбинацией:

```text
ts_rank_cd
+ field weight
+ domain boost
+ freshness boost
- deleted/done/closed penalty
```

Пример:

```java
finalScore = postgresRank
    + domainBoost
    + freshnessBoost
    - closedPenalty;
```

Результаты из разных providers нормализовать к диапазону `0..1`, чтобы общий merge был предсказуемым.

---

## Изменения в UI

На `/ui/search` отобразить:

- original query;
- selected layers;
- mode QUICK/DEEP;
- results grouped by layer;
- score;
- snippet;
- matched fields, если доступны;
- diagnostics, если какой-то provider упал или не ответил по timeout.

Для MVP matched fields можно не вычислять точно, но DTO поле оставить.

---

## Тестирование

### Unit tests

1. `SearchQueryParserTest`
   - разбивает русский запрос на keywords;
   - удаляет стоп-слова;
   - не падает на пустом запросе;
   - безопасно обрабатывает спецсимволы.

2. `TaskSearchProviderTest`
   - ищет задачу по title;
   - ищет задачу по description;
   - находит по форме слова;
   - не возвращает `DELETED`.

3. Аналогичные тесты для:
   - PeopleSearchProvider;
   - RiskSearchProvider;
   - IncidentSearchProvider;
   - NoticeSearchProvider.

---

### Integration tests

Добавить E2E сценарий:

```text
JavaMemoryService/test_e2e/12_global_search_tsvector.md
```

Сценарий:

1. создать задачу:

```text
Подготовить релиз payment-service
Описание: blocked, ждём согласование QA
```

2. создать риск:

```text
Релиз платежей может быть сорван из-за отсутствия согласования
```

3. создать incident:

```text
Платежи не проходят после релиза
```

4. вызвать:

```bash
curl -s -X POST http://localhost:8082/api/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query":"что зависло по релизу платежей",
    "layers":["TASKS","RISKS","INCIDENTS"],
    "mode":"QUICK",
    "limit":10
  }'
```

Expected:

- HTTP 200;
- результат содержит TASKS;
- результат содержит RISKS;
- результат содержит INCIDENTS;
- удалённые/закрытые записи не поднимаются выше активных;
- score присутствует.

---

### Migration tests

Проверить:

```sql
SELECT count(*) FROM memory.tasks WHERE search_vector IS NULL;
```

Expected:

```text
0 для существующих записей, где есть searchable fields
```

Проверить, что новая запись получает `search_vector` автоматически после INSERT.

Проверить, что `search_vector` меняется после UPDATE title/description/status.

---

## Acceptance Criteria

- [ ] Для всех non-RAG providers добавлен PostgreSQL `tsvector` search.
- [ ] Для существующих записей выполнен backfill `search_vector`.
- [ ] Новые записи автоматически получают заполненный `search_vector`.
- [ ] Обновление searchable полей обновляет `search_vector`.
- [ ] `KnowledgeSearchProvider` продолжает использовать JavaRagService, без перевода на PostgreSQL FTS.
- [ ] Пользовательский query разбивается на keywords через `SearchQueryParser`.
- [ ] Providers получают parsed query и ищут через PostgreSQL FTS.
- [ ] Providers запускаются параллельно в `GlobalSearchService`.
- [ ] Ошибка одного provider не ломает общий поиск.
- [ ] Результаты возвращаются с `layer`, `id`, `title`, `snippet`, `score`, `source`.
- [ ] Добавлен E2E сценарий для поиска по задачам, рискам и инцидентам.
- [ ] README/ARCHITECTURE/PRESENTATION при необходимости обновлены после реализации.

---

## Важные ограничения

1. Не использовать embeddings для operational entities в этом CR.
2. Не трогать RAG search.
3. Не делать прямой доступ внешних агентов к JavaRagService.
4. Global Search должен оставаться частью JavaMemoryService.
5. PostgreSQL FTS должен быть безопасным: пользовательский ввод не должен напрямую конкатенироваться в `to_tsquery` без очистки.

---

## Пример целевой архитектуры

```text
/ui/search
   |
   v
SearchController
   |
   v
SearchQueryParser
   |
   v
GlobalSearchService
   |
   +--> TaskSearchProvider      -> memory.tasks.search_vector
   +--> NoticeSearchProvider    -> memory.notes/notices.search_vector
   +--> PeopleSearchProvider    -> memory.people.search_vector
   +--> RiskSearchProvider      -> memory.risks.search_vector
   +--> IncidentSearchProvider  -> memory.incidents.search_vector
   +--> KnowledgeSearchProvider -> /api/knowledge/search -> JavaRagService
   |
   v
SearchResultMerger
   |
   +--> QUICK response
   +--> DEEP: SearchPromptBuilder + AgentClient
```

---

## Definition of Done

- Код собран без ошибок.
- Flyway миграции применяются на чистую и существующую БД.
- Все существующие тесты проходят.
- Новый E2E сценарий `12_global_search_tsvector.md` проходит.
- Поиск по запросу `что зависло по релизу платежей` находит релевантные tasks/risks/incidents без точного совпадения строки.
- В документации зафиксировано, что operational search использует PostgreSQL FTS, а knowledge search использует RAG.
