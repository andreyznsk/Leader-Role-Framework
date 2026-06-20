# CR-MEM-009: Разделить UI Memory Service на Operational Memory и Knowledge Gateway

**Дата:** 2026-06-20
**Статус:** Draft
**Сервис:** MEM / RAG / UI
**Зависимости:** CR-ARCH-005 Universal Knowledge UI

## Проблема / Мотивация

Сейчас в Memory Service есть UI `/ui/notes`, а также планируется UI для RAG-документов. Возникает путаница между двумя разными типами данных:

1. **Operational Notes** — быстрые рабочие заметки, capture, наблюдения, пометки по людям, встречам, рискам.
2. **RAG Knowledge** — стабильные знания, которые проходят lifecycle RAG: Markdown-файл → валидация → индексация → поиск.

Если оставить рядом `/ui/notes` и `/ui/notice`, пользователь будет путать notes и notice. Нужно явно разделить:

```text
Memory Service

├── Operational Memory
│      └── Notes
│
└── Knowledge Gateway
       └── RAG Knowledge
```

## Решение

Перестроить навигацию Memory Service UI вокруг двух смысловых зон.

### 1. Operational Memory

Операционная память техлида.

Содержит быстро меняющиеся данные:

* Today / план дня;
* Tasks;
* Risks;
* Incidents;
* People;
* Notes;
* Capture Inbox.

`/ui/notes` остаётся, но переименовывается в UI-навигации:

```text
Operational Memory → Notes
```

Назначение:

```text
Notes = рабочие заметки, не обязательно проиндексированные в RAG.
```

### 2. Knowledge Gateway

Шлюз к базе знаний RAG.

Содержит документы, которыми владеет JavaRagService:

* NOTICE;
* ADR;
* PROCESS;
* SERVICE_CARD;
* GLOSSARY;
* future document types.

Основной экран:

```text
/ui/knowledge
```

Название в UI:

```text
Knowledge Gateway → RAG Knowledge
```

`/ui/notice` не должен быть самостоятельным экраном. Он должен работать как redirect или preset-filter:

```text
/ui/notice → /ui/knowledge?type=NOTICE
```

## Изменения в UI

### Навигация

Новая структура меню:

```text
Operational Memory
  - Today
  - Tasks
  - Notes
  - Capture Inbox
  - Risks
  - Incidents
  - People

Knowledge Gateway
  - RAG Knowledge
  - Notices
  - ADR
  - Processes
  - Service Cards

Statistics
```

### Названия страниц

| URL             | UI Title               | Назначение                     |
| --------------- | ---------------------- | ------------------------------ |
| `/ui/notes`     | Operational Notes      | Рабочие заметки Memory Service |
| `/ui/knowledge` | RAG Knowledge          | Управление RAG-документами     |
| `/ui/notice`    | RAG Knowledge: Notices | Redirect/filter на NOTICE      |

## Архитектурные правила

1. `/ui/notes` работает только с Memory Service storage.
2. `/ui/knowledge` работает через JavaRagService REST API.
3. Memory Service не ходит напрямую в схему `rag`.
4. Notes не являются автоматически RAG knowledge.
5. RAG Knowledge — это только документы, прошедшие RAG lifecycle.
6. NOTICE из почты попадает не в notes, а в RAG Knowledge.

## Изменения в API

### Memory Service

Добавить или использовать proxy endpoints:

```text
GET /api/knowledge/documents
GET /api/knowledge/documents/{id}
PUT /api/knowledge/documents/{id}
POST /api/knowledge/documents/{id}/reindex
```

Для notes оставить существующий API:

```text
GET /api/notes
POST /api/notes
```

## Изменения в схеме БД

Новых таблиц не требуется.

Важно:

* не создавать таблицу `notice`;
* не дублировать RAG-документы в `memory.notes`;
* если нужны события, использовать `memory.usage_events`.

## E2E тесты

Добавить сценарий:

```text
JavaMemoryService/test_e2e/13_ui_memory_navigation.md
```

Проверяет:

1. `/ui/notes` доступен и содержит заголовок `Operational Notes`.
2. `/ui/knowledge` доступен и содержит заголовок `RAG Knowledge`.
3. `/ui/notice` делает redirect на `/ui/knowledge?type=NOTICE` или отображает NOTICE-filter.
4. В UI нет путаницы между Notes и RAG Knowledge.
5. Memory Service не использует JDBC-доступ к схеме `rag`.

## Как тестировать вручную

```bash
./test-runner/build.sh
./test-runner/start-services.sh --profile local
./test-runner/healthcheck.sh

curl -I http://localhost:8090/ui/notes
curl -I http://localhost:8090/ui/knowledge
curl -I http://localhost:8090/ui/notice
```

Ожидаемо:

```text
/ui/notes      → Operational Notes
/ui/knowledge  → RAG Knowledge
/ui/notice     → RAG Knowledge with type=NOTICE
```

## Критерии готовности

* В навигации явно разделены `Operational Memory` и `Knowledge Gateway`.
* `/ui/notes` не удалён.
* `/ui/notes` переосмыслен как `Operational Notes`.
* `/ui/knowledge` является основным экраном RAG-документов.
* `/ui/notice` не является отдельной сущностью, а ведёт в RAG Knowledge с фильтром NOTICE.
* Memory Service не обращается напрямую к БД RAG.
* Добавлены/обновлены E2E тесты.
* README/ARCHITECTURE обновлены.

## Ожидаемый результат

Пользователь видит ясное разделение:

```text
Notes = мои рабочие оперативные заметки.
RAG Knowledge = проиндексированная база знаний для AI.
```

Это убирает путаницу между `notes` и `notice` и делает UI LeaderOS понятнее для демо.
