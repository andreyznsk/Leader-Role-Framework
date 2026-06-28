# CR-MEM-009: Global Search with Layered Providers and Deep AI Summary

**Дата:** 2026-06-27  
**Статус:** Draft  
**Сервис:** MEM  
**Зависимости:** JavaMemoryService, JavaRagService, common AgentClient

## Проблема / Мотивация

В LeaderOS уже есть быстрые сущности для оперативной памяти: notice/notes, задачи, риски, инциденты, people notes и knowledge/RAG. Но пользователь не может быстро найти сохранённую информацию через единый интерфейс.

Типичный сценарий:

> "график отпусков по этой ссылке"

Такая информация может быть сохранена как notice, но позже пользователю нужно быстро найти её без понимания, где именно она лежит: в notice, задачах, people notes, документации или RAG.

Нужен единый Global Search по LeaderOS с возможностью включать/отключать слои поиска:

- только Notice;
- Notice + Tasks;
- всё LeaderOS;
- только Knowledge/RAG;
- люди и задачи;
- будущие слои: Mail, Calendar, GitHub, Jira, Confluence.

## Решение

Добавить в JavaMemoryService новый модуль `search`, включающий:

1. Новый UI `/ui/search`.
2. Поиск по слоям через чекбоксы/пресеты.
3. REST API `POST /api/search`.
4. Search engine с provider architecture.
5. Deep Search mode с AI summary через `AgentClient` из модуля `common`.
6. PromptBuilder для сборки промпта на основе query + найденных результатов.

## UX

### Страница `/ui/search`

Основной экран:

```text
Search LeaderOS

[ график отпусков __________________________ ] [ Search ]

Mode:
(o) Quick
( ) Deep AI Summary

Layers:
[x] Notice
[x] Tasks
[x] People
[x] Risks
[x] Incidents
[x] Knowledge / RAG
[ ] Mail       disabled / future
[ ] Calendar   disabled / future

Presets:
[ Notice only ] [ Everything ] [ Documentation ] [ People & Tasks ]
```

### Результаты

```text
Results for: "график отпусков"

AI Summary: optional, only for DEEP mode

NOTICE
- График отпусков команды
  Ссылка на актуальный график отпусков...
  URL: https://...
  score: 0.91

PEOPLE
- Иван Иванов
  Отпуск: 10–20 июля
  score: 0.74

KNOWLEDGE
- Team Vacation Process
  Source: Confluence / RAG
  score: 0.69
```

## Search Modes

### QUICK

Быстрый агрегированный поиск без вызова AI:

```text
query → selected providers → merge → sort → grouped response
```

### DEEP

Глубокий режим:

```text
query → selected providers → merge → top results → SearchPromptBuilder → AgentClient.complete(prompt) → summary + sources
```

Deep mode должен быть опциональным, чтобы быстрый поиск не зависел от LLM.

## Изменения в API

### `POST /api/search`

Request:

```json
{
  "query": "график отпусков",
  "layers": ["NOTICE", "TASK", "PEOPLE", "KNOWLEDGE"],
  "mode": "QUICK",
  "limit": 20
}
```

Response:

```json
{
  "query": "график отпусков",
  "mode": "QUICK",
  "layers": ["NOTICE", "TASK", "PEOPLE", "KNOWLEDGE"],
  "summary": null,
  "results": [
    {
      "layer": "NOTICE",
      "title": "График отпусков команды",
      "snippet": "Ссылка на актуальный график отпусков команды...",
      "url": "https://...",
      "entityId": "123",
      "entityType": "NOTICE",
      "score": 0.91,
      "updatedAt": "2026-06-27T10:00:00"
    }
  ]
}
```

### `GET /api/search/layers`

Возвращает доступные слои для UI.

Response:

```json
[
  { "name": "NOTICE", "title": "Notice", "enabled": true, "available": true },
  { "name": "TASK", "title": "Tasks", "enabled": true, "available": true },
  { "name": "PEOPLE", "title": "People", "enabled": true, "available": true },
  { "name": "KNOWLEDGE", "title": "Knowledge / RAG", "enabled": true, "available": true },
  { "name": "MAIL", "title": "Mail", "enabled": false, "available": false },
  { "name": "CALENDAR", "title": "Calendar", "enabled": false, "available": false }
]
```

## Search Domain Model

### `SearchLayer`

```java
public enum SearchLayer {
    NOTICE,
    TASK,
    PEOPLE,
    RISK,
    INCIDENT,
    KNOWLEDGE,
    MAIL,
    CALENDAR
}
```

### `SearchMode`

```java
public enum SearchMode {
    QUICK,
    DEEP
}
```

### `SearchRequest`

```java
public record SearchRequest(
    String query,
    List<SearchLayer> layers,
    SearchMode mode,
    Integer limit
) {}
```

### `SearchResultItem`

```java
public record SearchResultItem(
    SearchLayer layer,
    String title,
    String snippet,
    String url,
    String entityId,
    String entityType,
    double score,
    Instant updatedAt
) {}
```

### `SearchResponse`

```java
public record SearchResponse(
    String query,
    SearchMode mode,
    List<SearchLayer> layers,
    String summary,
    List<SearchResultItem> results
) {}
```

## Search Provider Architecture

### Interface

```java
public interface SearchProvider {
    SearchLayer layer();
    boolean supports(SearchLayer layer);
    List<SearchResultItem> search(String query, int limit);
}
```

### Providers MVP

```text
NoticeSearchProvider
TaskSearchProvider
PeopleSearchProvider
RiskSearchProvider
IncidentSearchProvider
KnowledgeSearchProvider
```

### Future providers

```text
MailSearchProvider
CalendarSearchProvider
GitHubSearchProvider
JiraSearchProvider
ConfluenceSearchProvider
```

## SearchService

```java
@Service
public class GlobalSearchService {

    private final List<SearchProvider> providers;
    private final SearchPromptBuilder promptBuilder;
    private final AgentClient agentClient;

    public SearchResponse search(SearchRequest request) {
        var layers = normalizeLayers(request.layers());
        var limit = normalizeLimit(request.limit());

        var results = providers.stream()
            .filter(provider -> layers.contains(provider.layer()))
            .flatMap(provider -> provider.search(request.query(), limit).stream())
            .sorted(Comparator.comparing(SearchResultItem::score).reversed())
            .limit(limit)
            .toList();

        String summary = null;
        if (request.mode() == SearchMode.DEEP && !results.isEmpty()) {
            var prompt = promptBuilder.build(request.query(), results);
            summary = agentClient.complete(prompt);
        }

        return new SearchResponse(request.query(), request.mode(), layers, summary, results);
    }
}
```

## Prompt Builder

### `SearchPromptBuilder`

Задача: превратить найденные результаты в компактный, безопасный промпт для AI summary.

Пример промпта:

```text
Ты — LeaderOS Knowledge Assistant.
Пользователь ищет: "график отпусков".

Сформируй краткий ответ:
1. Что найдено.
2. Где главный источник.
3. Что открыть первым.
4. Есть ли неопределённость.

Используй только результаты ниже. Не выдумывай факты.

RESULTS:
[NOTICE]
Title: График отпусков команды
Snippet: Ссылка на актуальный график отпусков...
URL: https://...
Score: 0.91

[PEOPLE]
Title: Иван Иванов
Snippet: Отпуск 10–20 июля
Score: 0.74
```

## Изменения в схеме БД

Для MVP можно обойтись без новой таблицы, если Notice/Tasks/People/Risks/Incidents уже лежат в PostgreSQL и доступны через repositories.

Если полнотекстовый поиск слабый, добавить индексы:

```sql
CREATE INDEX IF NOT EXISTS idx_notice_search_text
ON memory.notices USING gin (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(content, '')));

CREATE INDEX IF NOT EXISTS idx_tasks_search_text
ON memory.tasks USING gin (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, '')));
```

Если текущие таблицы называются иначе — агент должен адаптировать миграцию под фактическую модель JavaMemoryService.

## UI implementation notes

Использовать Thymeleaf, как остальные UI страницы JavaMemoryService.

Файлы ориентировочно:

```text
JavaMemoryService/src/main/java/.../search/
  SearchController.java
  GlobalSearchService.java
  SearchProvider.java
  SearchLayer.java
  SearchMode.java
  SearchRequest.java
  SearchResponse.java
  SearchResultItem.java
  SearchPromptBuilder.java
  provider/
    NoticeSearchProvider.java
    TaskSearchProvider.java
    PeopleSearchProvider.java
    RiskSearchProvider.java
    IncidentSearchProvider.java
    KnowledgeSearchProvider.java

JavaMemoryService/src/main/resources/templates/search.html
JavaMemoryService/src/main/resources/static/search.js   optional
JavaMemoryService/src/main/resources/db/migration/V*_search_indexes.sql optional
```

## KnowledgeSearchProvider

Должен ходить через существующий Memory-owned proxy к JavaRagService, а не обращаться к БД `rag` напрямую.

```text
KnowledgeSearchProvider
  → existing Knowledge/RAG client or RestTemplate/WebClient
  → JavaRagService /api/search
```

Важно: внешний UI и SearchService не должны получать прямой JDBC-доступ к схеме `rag`.

## Acceptance Criteria

1. В JavaMemoryService доступна страница `/ui/search`.
2. На странице есть поле поиска, выбор mode и чекбоксы слоёв.
3. Есть пресеты:
   - Notice only;
   - Everything;
   - Documentation;
   - People & Tasks.
4. `POST /api/search` принимает query, layers, mode, limit.
5. QUICK mode возвращает агрегированные результаты без вызова AI.
6. DEEP mode вызывает `AgentClient` из `common` и возвращает `summary`.
7. Для DEEP mode используется отдельный `SearchPromptBuilder`.
8. Поиск работает минимум по слоям:
   - NOTICE;
   - TASK;
   - PEOPLE;
   - RISK;
   - INCIDENT;
   - KNOWLEDGE.
9. Knowledge layer использует существующий Memory/RAG proxy, без прямого доступа к схеме `rag`.
10. Если слой недоступен, API не падает, а пропускает provider и логирует warning.
11. UI группирует результаты по layer.
12. Пустой результат отображается корректно.
13. Ошибка AgentClient в DEEP mode не ломает поиск: результаты возвращаются, summary содержит понятное сообщение или `null`.
14. Добавлены E2E сценарии для `/api/search` и `/ui/search`.

## Как тестировать

### Unit

1. `GlobalSearchService`:
   - фильтрует providers по layers;
   - сортирует по score;
   - уважает limit;
   - не вызывает AgentClient в QUICK mode;
   - вызывает AgentClient в DEEP mode.

2. `SearchPromptBuilder`:
   - строит prompt только из найденных результатов;
   - не превышает разумный лимит контекста;
   - явно запрещает выдумывать факты.

3. Providers:
   - каждый provider возвращает корректный `SearchResultItem`.

### Integration / E2E

Создать `JavaMemoryService/test_e2e/12_global_search.md`:

```markdown
# Scenario: Global Search

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres

## Steps

### Step 1 — Create notice with vacation link
```bash
curl -s -X POST "$MS_URL/api/notices" \
  -H "Content-Type: application/json" \
  -d '{"title":"E2E график отпусков","content":"График отпусков команды https://example.com/vacation","tags":["vacation","team"]}'
```
**Expected:** HTTP 201 or 200

### Step 2 — Search notice only
```bash
curl -s -X POST "$MS_URL/api/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"график отпусков","layers":["NOTICE"],"mode":"QUICK","limit":10}'
```
**Expected:** HTTP 200, contains `E2E график отпусков`, layer = `NOTICE`

### Step 3 — Search everything
```bash
curl -s -X POST "$MS_URL/api/search" \
  -H "Content-Type: application/json" \
  -d '{"query":"график отпусков","layers":["NOTICE","TASK","PEOPLE","RISK","INCIDENT","KNOWLEDGE"],"mode":"QUICK","limit":10}'
```
**Expected:** HTTP 200, contains results array

### Step 4 — UI smoke
```bash
curl -s "$MS_URL/ui/search"
```
**Expected:** HTTP 200, contains `Search LeaderOS`
```

## Документация

Обновить:

```text
ARCHITECTURE.md
README.md
AGENT.md
```

В документации зафиксировать:

- `/ui/search` как единый Global Search UI;
- `POST /api/search`;
- список слоёв;
- QUICK/DEEP режимы;
- правило: DEEP summary использует `AgentClient` из common;
- правило: Knowledge layer идёт через Memory-owned proxy к JavaRagService.

## Риски

1. Поиск по разным сущностям может иметь разное качество ранжирования.
2. Deep mode может быть медленным из-за AI вызова.
3. Нужно не допустить прямой зависимости MemoryService от схемы `rag`.
4. Нужно не превратить Notice UI в сложную систему классификации — Notice остаётся быстрым capture.

## Out of Scope

Не входит в этот CR:

- поиск по Mail;
- поиск по Calendar;
- поиск по Jira/Confluence напрямую;
- отдельный Docs Catalog;
- изменение модели Notice, если это не требуется для поиска;
- автоматическое извлечение title/tags из ссылки.

## Следующие CR

1. `CR-MEM-010-notice-quick-save-ui` — улучшить Notice UI для быстрого сохранения ссылок/текста.
2. `CR-MEM-011-search-mail-calendar-layers` — добавить Mail и Calendar providers.
3. `CR-MEM-012-search-ai-actions` — из результата поиска предлагать действие: создать задачу, риск, открыть документ, сохранить в notice.
