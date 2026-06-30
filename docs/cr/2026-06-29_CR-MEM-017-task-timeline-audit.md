# 2026-06-29_CR-MEM-017: Task Timeline Audit

_Переименован из CR-MEM-012 для устранения коллизии номеров, 2026-06-30._

**Дата:** 2026-06-29  
**Статус:** Implemented  
**Сервис:** MEM  
**Зависимости:** JavaMemoryService, UI Today, Pending Task Flow, future Intelligent Mail Linking

## Проблема / Мотивация

Сейчас задача хранит в основном текущее состояние: title, description, priority, status, dueDate/date.

Из-за этого теряется история: кто изменил статус, когда редактировалось описание, почему задача была отклонена, из какого письма она появилась и с каким контекстом связана.

Для LeaderOS задача должна быть не просто карточкой, а рабочим досье с историей событий.

Также hard delete задач нежелателен: удаление из UI не должно физически уничтожать контекст. Задача должна уходить в архив.

## Решение

Добавить task timeline / audit model в JavaMemoryService.

Основной принцип:

```text
Любое значимое изменение задачи создает immutable timeline event.
```

События должны создаваться для:

- создания задачи;
- создания PENDING-задачи;
- подтверждения PENDING в TODO;
- смены статуса;
- изменения title;
- изменения description;
- изменения priority;
- изменения dueDate/date;
- архивирования задачи;
- связывания внешнего источника с задачей;
- добавления agent summary;
- ручного комментария пользователя.

Hard delete задач запрещается для пользовательского flow. Удаление из UI переводит задачу в `ARCHIVED`.

## Статусная модель

Текущая модель:

```text
PENDING -> TODO -> IN_PROGRESS -> DONE / DELETED
```

Новая модель:

```text
PENDING -> TODO -> IN_PROGRESS -> DONE / ARCHIVED
```

`DELETED` становится legacy-статусом. Для новых пользовательских сценариев использовать `ARCHIVED`.

Важно: `LINKED` не должен становиться основным статусом обычной задачи. Связывание письма или pending-кандидата с задачей должно быть событием timeline, а не статусом основной задачи.

## Изменения в API

### Получить timeline задачи

```http
GET /api/tasks/{id}/timeline
```

Ответ содержит список событий задачи: eventType, actorType, actorName, oldValue, newValue, sourceType, sourceId, summary, createdAt.

### Добавить комментарий в timeline

```http
POST /api/tasks/{id}/timeline/comment
```

Body:

```json
{
  "text": "Комментарий пользователя по задаче"
}
```

### Архивировать задачу

```http
POST /api/tasks/{id}/archive
```

Либо существующий delete endpoint должен быть изменен так, чтобы переводить задачу в `ARCHIVED`, а не удалять физически.

### Изменить существующие update endpoints

Все update endpoints задач должны создавать timeline events:

- status update -> STATUS_CHANGED;
- title update -> TITLE_UPDATED;
- description update -> DESCRIPTION_UPDATED;
- priority update -> PRIORITY_CHANGED;
- dueDate/date update -> DUE_DATE_CHANGED;
- pending confirm -> PENDING_CONFIRMED;
- pending reject/archive -> TASK_ARCHIVED.

## Изменения в схеме БД

Добавить таблицу `memory.task_events`.

Минимальные поля:

```text
id
task_id
event_type
actor_type
actor_name
old_value_json
new_value_json
source_type
source_id
summary
created_at
```

Индексы:

```text
task_id + created_at
source_type + source_id
```

Рекомендуемые `event_type`:

```text
TASK_CREATED
PENDING_TASK_CREATED
PENDING_CONFIRMED
STATUS_CHANGED
TITLE_UPDATED
DESCRIPTION_UPDATED
PRIORITY_CHANGED
DUE_DATE_CHANGED
TASK_ARCHIVED
EMAIL_LINKED
CAPTURE_LINKED
AGENT_SUMMARY_ADDED
COMMENT_ADDED
```

Рекомендуемые `actor_type`:

```text
USER
AGENT
MAIL_AGENT
CAPTURE_BOT
SYSTEM
API
```

Рекомендуемые `source_type`:

```text
UI
EMAIL
CAPTURE
AGENT
API
SYSTEM
```

## UI изменения

На странице задачи добавить блок `Timeline`.

Пример отображения:

```text
Timeline
- 2026-06-29 15:30 — Статус изменен TODO -> IN_PROGRESS
- 2026-06-29 16:10 — Описание обновлено пользователем
- 2026-06-29 17:20 — Задача связана с письмом RE: Release
```

В Today UI и Pending UI:

- удаление задачи должно архивировать задачу;
- pending reject должен архивировать задачу;
- pending confirm должен создавать timeline event.

## Зависимости

Эта доработка является фундаментом для следующего CR:

```text
2026-06-29_CR-MAIL-005-intelligent-mail-linking.md
```

Без timeline невозможно корректно отображать, что входящее письмо было связано с существующей задачей.

## Как тестировать

1. Создать PENDING-задачу через `POST /api/tasks/pending`.
2. Проверить событие `PENDING_TASK_CREATED`.
3. Подтвердить pending.
4. Проверить событие `PENDING_CONFIRMED`.
5. Изменить статус.
6. Проверить событие `STATUS_CHANGED` с old/new value.
7. Изменить описание.
8. Проверить событие `DESCRIPTION_UPDATED`.
9. Архивировать задачу.
10. Проверить статус `ARCHIVED` и событие `TASK_ARCHIVED`.
11. Открыть задачу в UI и проверить отображение timeline.
12. Проверить, что архивные задачи не отображаются в обычных списках по умолчанию.

## Acceptance Criteria

- [ ] Добавлена таблица `memory.task_events`.
- [ ] Добавлен статус `ARCHIVED`.
- [ ] Hard delete задач в пользовательском flow заменен на archive.
- [ ] Все значимые изменения задачи создают timeline event.
- [ ] Добавлен API `GET /api/tasks/{id}/timeline`.
- [ ] Добавлен API для ручного комментария в timeline.
- [ ] UI задачи отображает timeline.
- [ ] Pending confirm/reject создают timeline events.
- [ ] Старые задачи без events корректно отображаются.
- [ ] Добавлены E2E-сценарии для task timeline и archive flow.
