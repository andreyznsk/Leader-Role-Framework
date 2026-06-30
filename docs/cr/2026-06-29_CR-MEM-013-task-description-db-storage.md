# 2026-06-29_CR-MEM-013: Task description DB storage and searchable markdown

**Дата:** 2026-06-29  
**Статус:** Implemented  
**Сервис:** MEM  
**Зависимости:** CR-MEM-012 Global Search tsvector providers, JavaMemoryService, PostgreSQL, UI Today/Task details  
**Связанный Issue:** TBD  

---

## Проблема / Мотивация

В текущей модели JavaMemoryService расширенное описание задачи хранится в markdown-файлах на диске:

```text
workspace/tasks/TASK-{id}.md
```

Это удобно для агент-читаемости и ручной инспекции, но плохо ложится на новый Global Search через PostgreSQL `tsvector` из CR-MEM-012.

Проблема:

- `memory.tasks.search_vector` сможет искать только по коротким полям задачи;
- длинное markdown-описание останется вне PostgreSQL full-text search;
- TaskSearchProvider не сможет находить задачи по важному контексту из описания;
- файловая система становится вторым source of truth для operational memory;
- синхронизация БД ↔ markdown-файл усложняет консистентность.

Пример:

```text
Задача: Подготовить релиз payment-service
Описание в md: blocked, ждём согласование QA, есть риск сдвига релиза
```

Запрос:

```text
что зависло по согласованию qa
```

Если описание лежит только на диске, PostgreSQL search по задачам эту задачу может не найти.

---

## Решение

Перенести расширенные markdown-описания задач из файловой системы в PostgreSQL, в отдельную таблицу `memory.task_descriptions`.

Файлы `workspace/tasks/TASK-{id}.md` больше не должны быть source of truth. Вместо этого markdown должен храниться в БД, а файл должен создаваться только по явному запросу пользователя через export.

Целевая модель:

```text
memory.tasks
  ├─ id
  ├─ title
  ├─ status
  ├─ priority
  ├─ date
  ├─ due_date
  ├─ source
  └─ search_vector

memory.task_descriptions
  ├─ id
  ├─ task_id FK -> memory.tasks.id
  ├─ content_md
  ├─ content_hash
  ├─ search_vector
  ├─ created_at
  └─ updated_at
```

TaskSearchProvider должен искать одновременно по:

```text
memory.tasks.search_vector
+
memory.task_descriptions.search_vector
```

---

## Область изменений

В рамках CR нужно изменить только JavaMemoryService.

Затрагиваемые области:

- PostgreSQL schema/migrations;
- Task entity / repository / service layer;
- API чтения и обновления описания задачи;
- UI Today / Task details;
- markdown export endpoint;
- TaskSearchProvider;
- E2E сценарии.

Не затрагивать:

- JavaRagService;
- KnowledgeSearchProvider;
- RAG индексацию;
- Mail Agent routing, кроме случаев когда он создаёт pending task с description.

---

## Изменения в схеме БД

### 1. Новая таблица `memory.task_descriptions`

```sql
CREATE TABLE IF NOT EXISTS memory.task_descriptions (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL UNIQUE REFERENCES memory.tasks(id) ON DELETE CASCADE,
    content_md TEXT NOT NULL DEFAULT '',
    content_hash VARCHAR(64),
    search_vector tsvector,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

### 2. Индекс для поиска

```sql
CREATE INDEX IF NOT EXISTS idx_task_descriptions_search_vector
ON memory.task_descriptions USING GIN(search_vector);
```

### 3. Индекс по `task_id`

```sql
CREATE INDEX IF NOT EXISTS idx_task_descriptions_task_id
ON memory.task_descriptions(task_id);
```

### 4. Trigger для `search_vector`

```sql
CREATE OR REPLACE FUNCTION memory.update_task_description_search_vector()
RETURNS trigger AS $$
BEGIN
  NEW.search_vector :=
      setweight(to_tsvector('russian', coalesce(NEW.content_md, '')), 'A') ||
      setweight(to_tsvector('english', coalesce(NEW.content_md, '')), 'B');
  NEW.updated_at := now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_task_descriptions_search_vector ON memory.task_descriptions;

CREATE TRIGGER trg_task_descriptions_search_vector
BEFORE INSERT OR UPDATE OF content_md
ON memory.task_descriptions
FOR EACH ROW
EXECUTE FUNCTION memory.update_task_description_search_vector();
```

### 5. Backfill из существующих md-файлов

Нужно выполнить миграцию данных из файлов:

```text
workspace/tasks/TASK-{id}.md
```

в таблицу:

```text
memory.task_descriptions
```

Так как Flyway SQL не должен напрямую читать произвольную файловую систему приложения, backfill лучше сделать application-level миграцией/one-shot runner:

```text
TaskDescriptionFileMigrationRunner
```

Требования:

- запускается один раз после старта приложения;
- читает существующие `workspace/tasks/TASK-{id}.md`;
- извлекает task id из имени файла;
- если задача существует в `memory.tasks`, создаёт или обновляет `memory.task_descriptions`;
- считает `content_hash`;
- не удаляет файлы сразу;
- после успешной миграции может пометить файлы как migrated или оставить read-only backup;
- повторный запуск должен быть идемпотентным.

---

## Изменения в API

### 1. Получить описание задачи

```http
GET /api/tasks/{id}/description
```

Response:

```json
{
  "taskId": 123,
  "contentMd": "## Context\n...",
  "contentHash": "...",
  "updatedAt": "2026-06-29T10:15:00"
}
```

### 2. Обновить описание задачи

```http
PUT /api/tasks/{id}/description
Content-Type: application/json
```

Request:

```json
{
  "contentMd": "## Context\nНужно согласовать релиз payment-service с QA"
}
```

Behavior:

- создаёт описание, если его ещё нет;
- обновляет `content_md`;
- обновляет `content_hash`;
- `search_vector` обновляется trigger-ом;
- не пишет markdown-файл на диск.

### 3. Export markdown-файла

Добавить endpoint:

```http
GET /api/tasks/{id}/description/export-md
```

Response:

```http
Content-Type: text/markdown; charset=utf-8
Content-Disposition: attachment; filename="TASK-{id}.md"
```

Body:

```markdown
# TASK-123: Подготовить релиз payment-service

**Status:** IN_PROGRESS  
**Priority:** HIGH  
**Date:** 2026-06-29  

---

## Description

...
```

---

## Изменения в UI

### Task details / Today UI

В UI задачи нужно:

- показывать markdown-описание из БД;
- редактировать markdown-описание через существующий или новый editor;
- сохранять через `PUT /api/tasks/{id}/description`;
- больше не писать описание напрямую в `workspace/tasks/TASK-{id}.md`;
- добавить кнопку:

```text
Export MD
```

Кнопка вызывает:

```text
GET /api/tasks/{id}/description/export-md
```

и скачивает файл `TASK-{id}.md`.

---

## Изменения в TaskSearchProvider

После CR-MEM-012 TaskSearchProvider должен искать не только по `memory.tasks.search_vector`, но и по `memory.task_descriptions.search_vector`.

Пример SQL:

```sql
SELECT
    t.id,
    t.title,
    t.status,
    t.priority,
    d.content_md,
    ts_rank_cd(t.search_vector, websearch_to_tsquery('russian', :query)) AS task_rank,
    ts_rank_cd(d.search_vector, websearch_to_tsquery('russian', :query)) AS description_rank
FROM memory.tasks t
LEFT JOIN memory.task_descriptions d ON d.task_id = t.id
WHERE t.status <> 'DELETED'
  AND (
      t.search_vector @@ websearch_to_tsquery('russian', :query)
      OR d.search_vector @@ websearch_to_tsquery('russian', :query)
  )
ORDER BY
    (coalesce(task_rank, 0) * 1.2 + coalesce(description_rank, 0)) DESC,
    t.updated_at DESC
LIMIT :limit;
```

Рекомендованный scoring:

```text
title/task fields match       высокий вес
description content match     высокий/средний вес
IN_PROGRESS/TODO boost        выше DONE
HIGH priority boost           выше MEDIUM/LOW
overdue boost                 выше обычных
```

SearchResult snippet должен уметь показывать фрагмент из `content_md`, если match найден в описании.

---

## Миграционная политика файлов

После переноса source of truth меняется:

```text
Было:
workspace/tasks/TASK-{id}.md = source of truth для длинного описания

Стало:
memory.task_descriptions.content_md = source of truth
workspace/tasks/TASK-{id}.md = export/backup только по запросу
```

Правила:

1. Новые задачи не должны автоматически создавать `workspace/tasks/TASK-{id}.md`.
2. Изменение описания задачи не должно писать md-файл на диск.
3. Существующие файлы нужно импортировать в БД.
4. Старые файлы после импорта не удалять автоматически в рамках MVP.
5. Если файл и БД расходятся после миграции, БД имеет приоритет.
6. Экспорт md всегда генерируется из БД.

---

## Зависимости от CR-MEM-012

CR-MEM-012 вводит общий механизм Global Search через PostgreSQL FTS для operational providers.

CR-MEM-013 расширяет этот механизм для задач:

```text
CR-MEM-012
  -> tasks.search_vector

CR-MEM-013
  -> task_descriptions.search_vector
  -> TaskSearchProvider searches both task fields and markdown description
```

Если CR-MEM-013 реализуется раньше CR-MEM-012, нужно всё равно подготовить таблицу и `search_vector`, но подключение к TaskSearchProvider выполнить после появления общего SearchProvider-контракта.

---

## Тестирование

### Unit tests

1. `TaskDescriptionServiceTest`
   - создаёт description для задачи;
   - обновляет description;
   - считает content hash;
   - не пишет файл на диск;
   - возвращает not found для неизвестной задачи.

2. `TaskDescriptionMarkdownExportTest`
   - экспортирует markdown;
   - содержит title/status/priority/date;
   - содержит `content_md`;
   - отдаёт корректный filename.

3. `TaskSearchProviderTest`
   - находит задачу по title;
   - находит задачу по `task_descriptions.content_md`;
   - поднимает активные задачи выше DONE;
   - не возвращает DELETED.

---

### Integration/E2E tests

Добавить сценарий:

```text
JavaMemoryService/test_e2e/13_task_description_db_storage.md
```

Сценарий:

1. создать задачу;
2. сохранить markdown description через API;
3. прочитать description через API;
4. выполнить search по слову, которое есть только в description;
5. убедиться, что задача найдена;
6. вызвать export-md;
7. убедиться, что response markdown содержит описание.

Пример search query:

```text
согласование qa blocked
```

Expected:

- задача находится через TaskSearchProvider;
- `snippet` содержит фрагмент из markdown description;
- md export работает;
- файл на диск не создаётся автоматически.

---

## Acceptance Criteria

- [ ] Создана таблица `memory.task_descriptions`.
- [ ] `task_id` связан с `memory.tasks.id` через FK.
- [ ] `content_md` хранит markdown-описание задачи.
- [ ] Для `content_md` создаётся и обновляется `search_vector`.
- [ ] Существующие `workspace/tasks/TASK-{id}.md` импортируются в БД идемпотентно.
- [ ] Новые/изменённые описания больше не пишутся автоматически в md-файлы на диск.
- [ ] БД становится source of truth для task description.
- [ ] Добавлен API `GET /api/tasks/{id}/description`.
- [ ] Добавлен API `PUT /api/tasks/{id}/description`.
- [ ] Добавлен API `GET /api/tasks/{id}/description/export-md`.
- [ ] В UI добавлена кнопка `Export MD`.
- [ ] TaskSearchProvider ищет по `tasks.search_vector` и `task_descriptions.search_vector`.
- [ ] Поиск находит задачу по словам, которые есть только в markdown description.
- [ ] Добавлен E2E сценарий `13_task_description_db_storage.md`.
- [ ] README/ARCHITECTURE обновлены: файлы task markdown больше не source of truth, только export/backup.

---

## Важные ограничения

1. Не индексировать task descriptions в RAG.
2. Не делать JavaRagService владельцем operational task descriptions.
3. Не удалять существующие md-файлы автоматически при первом релизе.
4. Не ломать существующие API редактирования описания задач: если есть старый endpoint, сохранить backward compatibility или сделать адаптер.
5. Export MD должен генерироваться из БД, а не читать старый файл.

---

## Definition of Done

- Flyway миграции применяются на чистую и существующую БД.
- Existing task markdown descriptions импортируются в `memory.task_descriptions`.
- UI показывает и сохраняет описание из БД.
- Export MD скачивает корректный markdown-файл.
- TaskSearchProvider находит задачи по тексту markdown-описания.
- Все существующие тесты проходят.
- Новый E2E сценарий проходит.
- Документация обновлена под новую source-of-truth модель.

---

## Фактическая реализация

**Реализовано:** 2026-06-29 (коммит `b1c3dfb`)  
Полный стек подтверждён:
- Domain: `TaskDescription`
- Service/Controller/Repository: `TaskDescriptionService`, `TaskDescriptionController`, `TaskDescriptionRepository`
- DTO: `TaskDescriptionResponse`, `UpdateTaskDescriptionRequest`
- Миграция: `V14__task_description_storage.java`
- Файловая миграция: `TaskDescriptionFileMigrationRunner`
- Тесты на каждый слой
