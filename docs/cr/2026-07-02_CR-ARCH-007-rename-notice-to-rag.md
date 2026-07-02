# 2026-07-02_CR-ARCH-007: Rename NOTICE terminology to RAG

**Дата:** 2026-07-02  
**Статус:** Draft  
**Сервис:** ARCH / MAIL / MEM / RAG  
**Тип:** cross-service terminology refactoring  
**Ветка:** `feature/ARCH-007-2026-07-02`

---

## Проблема / Мотивация

В текущей модели LeaderOS пересекаются понятия `NOTE` и `NOTICE`.

`NOTE` означает operational note в Memory Service.  
`NOTICE` по факту используется как knowledge/RAG flow и уже маршрутизируется в `suggestedRoute=RAG`.

Из-за этого терминология неоднозначна: пользователь видит два похожих слова для разных смыслов. Нужно убрать `NOTICE` как отдельную доменную сущность и заменить его на `RAG` там, где речь идет о долговременных знаниях и индексации.

---

## Решение

Ввести единую терминологию:

```text
NOTE = operational note в Memory Service
RAG  = knowledge document / semantic knowledge flow
```

Новая модель маршрутов:

```text
REQUEST → задача / intake TASK
NOTE    → operational note
RAG     → knowledge document / RAG indexing flow
CAPTURE → raw capture
DRAFT   → черновик ответа
NOISE   → шум
```

---

## Изменения по сервисам

### JavaMailAgent

- `AgentResponseType.NOTICE` заменить на `AgentResponseType.RAG`.
- В prompt классификации заменить маршрут `NOTICE` на `RAG`.
- Parser должен принимать новый тип `RAG`.
- Legacy `NOTICE` должен поддерживаться как alias и нормализоваться в `RAG`.
- Checkpoint `NOTICE_WRITE` переименовать в RAG-ориентированное имя, например `RAG_WRITE` / `RAG_INTAKE`.
- Логи, UI status, тестовые данные и документация не должны использовать `NOTICE` как актуальный термин.

### JavaMemoryService

- `NOTE` оставить только для operational notes.
- `RAG` оставить canonical route для knowledge items.
- Intake Gateway не должен показывать `NOTICE` как отдельный route.
- Global Search должен разделять `Notes` и `RAG / Knowledge Base`.
- `NoticeSearchProvider`, если он есть, переименовать по фактическому смыслу:
  - `NoteSearchProvider`, если ищет operational notes;
  - `RagSearchProvider` / `KnowledgeSearchProvider`, если ищет RAG documents.

### JavaRagService

- Использовать термины `RAG`, `RAG document`, `Knowledge Base`.
- Не использовать `NOTICE` для новых UI-facing и architecture-facing текстов.
- Не менять существующие Flyway migration-файлы.

### Capture Bot

Унифицировать classification route:

```text
KNOWLEDGE → RAG
```

На переходный период `KNOWLEDGE` можно принимать как legacy alias, но внутри системы показывать и сохранять как `RAG`.

---

## Изменения в API

Было:

```text
REQUEST / DRAFT / NOISE / CAPTURE / NOTICE / NOTE
```

Станет:

```text
REQUEST / DRAFT / NOISE / CAPTURE / RAG / NOTE
```

Требование совместимости:

```text
incoming NOTICE → normalize to RAG
incoming KNOWLEDGE → normalize to RAG where applicable
```

В новых API responses `NOTICE` не должен возвращаться как canonical value.

---

## Изменения в схеме БД

MVP: без изменений схемы БД.

Запрещено менять существующие миграции:

```text
*/db/migration
```

Если в БД есть исторические значения `NOTICE`, обработать их через compatibility / normalization layer. Если потребуется data migration — оформить отдельный CR.

---

## Acceptance Criteria

1. В актуальной доменной модели нет отдельного route/entity `NOTICE`.
2. MailAgent классифицирует knowledge-письма как `RAG`.
3. Legacy ответ агента `NOTICE` не ломает обработку и нормализуется в `RAG`.
4. `NOTE` означает только operational note.
5. `RAG` означает долговременное знание / document flow / semantic search.
6. Intake UI показывает `RAG`, но не `NOTICE`.
7. Global Search разделяет `Notes` и `RAG / Knowledge Base`.
8. E2E тесты покрывают новый `RAG` route и legacy alias `NOTICE`.
9. Документация обновлена после реализации: `ARCHITECTURE.md`, RFC сервисов, README / presentation при необходимости.
10. Flyway migration-файлы не изменялись.

---

## Как тестировать

### Parser / unit tests

Проверить:

```text
input RAG     → route RAG
input NOTICE  → route RAG
input NOTE    → route NOTE
```

### E2E MailAgent

Добавить или обновить сценарий mail-to-rag:

1. Отправить письмо с текстом инструкции / документации.
2. Запустить poll cycle.
3. Проверить intake item с `suggestedRoute=RAG`.
4. Проверить, что письмо обработано и не создает NOTE.

### E2E MemoryService

Проверить:

- Intake Gateway не показывает `NOTICE`.
- Operational notes остаются `NOTE`.
- Search UI показывает Notes и RAG как разные слои.

---

## План реализации для агента

1. Найти все вхождения `NOTICE`, `Notice`, `notice` в коде, тестах, prompt-ах и документации.
2. Разделить runtime-код и исторические документы. Исторические CR / reports не переписывать без необходимости.
3. Выполнить targeted rename в runtime-коде.
4. Добавить compatibility alias `NOTICE → RAG`.
5. Добавить / обновить E2E сценарии.
6. Запустить build и профильные E2E.
7. После подтверждения пользователя обновить RFC / ARCHITECTURE и перевести CR в `Implemented`.

---

## Риски

- В БД могут быть старые значения `NOTICE`.
- Mock prompts могут продолжать возвращать `NOTICE`.
- `NoticeSearchProvider` может быть связан не с knowledge, а с operational notes.

Митигация:

- Не делать blind rename.
- Legacy alias сохранить на переходный период.
- Search provider переименовывать по фактическому назначению.

---

## Definition of Done

- [ ] Runtime-код использует `RAG` вместо `NOTICE` для knowledge route.
- [ ] Legacy `NOTICE` поддержан как alias.
- [ ] UI не показывает `NOTICE` как актуальную сущность.
- [ ] Search providers приведены к `Note` / `RAG` терминологии.
- [ ] E2E тесты добавлены / обновлены.
- [ ] Документация обновлена после реализации.
- [ ] CR переведён в `Implemented` после подтверждения пользователя.
- [ ] `docs/cr/REGISTRY.md` обновлён.
