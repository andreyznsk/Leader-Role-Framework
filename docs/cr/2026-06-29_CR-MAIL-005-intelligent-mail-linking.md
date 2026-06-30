# 2026-06-29_CR-MAIL-005: Intelligent Mail Linking

**Дата:** 2026-06-29  
**Статус:** In Progress / Partially Implemented  
**Сервис:** MAIL / MEM  
**Зависимости:** JavaMailAgent, JavaMemoryService, Global Search, common AgentClient, CR-MEM-012 Task Timeline Audit, CR-MEM-012 Global Search tsvector providers

## Проблема / Мотивация

Изначальный flow Mail Agent был простым:

```text
новое письмо -> классификация -> REQUEST -> новая PENDING-задача
```

Проблема в том, что ответ в существующей email-цепочке через час, день или неделю интерпретировался как новая работа. В результате Mail Agent создавал дублирующие PENDING-задачи, хотя по смыслу письмо было продолжением уже существующей задачи.

Целевое поведение:

```text
письмо = новый сигнал, который может быть
- новой задачей;
- продолжением существующей задачи;
- обновлением существующей задачи;
- шумом без action item.
```

## Текущий статус реализации

На момент обновления этого CR часть решения уже реализована в коде.

### Уже реализовано

- В `JavaMailAgent` добавлен отдельный `MailLinkingService`.
- Перед созданием PENDING-задачи для `REQUEST` Mail Agent вызывает `POST /api/search` в `JavaMemoryService`.
- Добавлен `MailLinkingPromptBuilder` и отдельный runtime prompt `mailLinkingPrompt`.
- Поддержаны решения:
  - `NEW_TASK`
  - `LINK_TO_TASK`
  - `UPDATE_TASK`
  - `IGNORE`
  - `REQUEST_CONFIRMATION`
- Результат linking-решения прокидывается в `PendingTaskRequest` и сохраняется в `JavaMemoryService`.
- В `JavaMemoryService` расширена модель `tasks` полями mail-linking metadata.
- Добавлен endpoint `POST /api/tasks/pending/{id}/link`.
- В UI pending-секции появились действия для linking/update-кандидатов.
- При связывании в timeline целевой задачи записывается `EMAIL_LINKED`.
- Для `UPDATE_TASK` уже поддержан append в description целевой задачи.

### Еще не закрыто

- E2E-сценарии для duplicate email prevention и веток mail linking добавлены, но их нужно прогнать на полном стенде через test-runner.
- Текущий search query builder реализован как MVP и использует не весь контекст из исходного CR.
- Ошибки в mail-linking flow сейчас fail-open: при исключении сохраняется старое classification-решение `REQUEST`.

## Целевой pipeline

```text
New Email
  -> Mail Agent
  -> Classification Prompt
  -> REQUEST
  -> Search Query Builder
  -> JavaMemoryService /api/search
  -> Global Search results
  -> Mail Linking Prompt Builder
  -> AgentClient
  -> Mail Linking Decision
  -> createPending(...) или IGNORE
  -> JavaMemoryService action / UI confirmation
```

## Реализованный flow

Текущий flow работает так:

1. Mail Agent классифицирует письмо обычным classification prompt.
2. Дополнительный linking-flow запускается только для `REQUEST`.
3. `MailLinkingService` собирает search query из:
   - normalized subject;
   - `from`;
   - сокращенного body.
4. Mail Agent вызывает `POST /api/search` с mode `QUICK`, limit `8`.
5. В prompt передаются:
   - `email`;
   - `classification`;
   - `search`.
6. AgentClient возвращает JSON-решение mail linking.
7. Решение мержится в `AgentResponse`.
8. `ActionExecutor`:
   - для `NEW_TASK` создает обычную PENDING-задачу;
   - для `LINK_TO_TASK` и `UPDATE_TASK` создает linking/update pending-кандидат;
   - для `REQUEST_CONFIRMATION` создает pending-кандидат с альтернативой "Создать новую задачу";
   - для `IGNORE` переводит письмо в `NOISE` и не создает задачу.

## Решения агента

Минимальные типы решений:

```text
NEW_TASK
LINK_TO_TASK
UPDATE_TASK
IGNORE
REQUEST_CONFIRMATION
```

### NEW_TASK

Письмо действительно является новой работой.

Действие:

```text
создать новую PENDING-задачу
```

### LINK_TO_TASK

Письмо относится к существующей задаче, но не требует изменения ее текущих полей.

Текущее действие:

```text
создать pending-кандидат на связывание
```

После подтверждения пользователя:

```text
pending-кандидат архивируется
в timeline существующей задачи появляется EMAIL_LINKED
```

### UPDATE_TASK

Письмо относится к существующей задаче и содержит значимое обновление: новый срок, новое решение, новый риск, новая договоренность.

Текущее действие:

```text
создать pending-кандидат на обновление задачи
```

После подтверждения пользователя через link-action:

```text
обновить description существующей задачи append-блоком
добавить timeline event EMAIL_LINKED
добавить DESCRIPTION_UPDATED через TaskDescriptionService
```

Примечание: отдельный event `AGENT_SUMMARY_ADDED` пока не реализован.

### IGNORE

Письмо не требует действий.

Текущее действие:

```text
не создавать задачу
вернуть итоговый AgentResponseType = NOISE
обработать письмо по текущим mail side-effect rules
```

### REQUEST_CONFIRMATION

Агент не уверен.

Текущее действие:

```text
создать PENDING-кандидат
дать пользователю выбор создать новую задачу или работать с suggested task, если она есть
```

## Prompt

В `JavaMailAgent` уже добавлен отдельный prompt template `mailLinkingPrompt`.

Текущий ожидаемый JSON:

```json
{
  "decision": "<NEW_TASK|LINK_TO_TASK|UPDATE_TASK|IGNORE|REQUEST_CONFIRMATION>",
  "confidence": 0.0,
  "targetTaskId": 142,
  "title": "string or null",
  "summary": "string or null",
  "reason": "string",
  "proposedDescriptionAppend": "string or null",
  "matchedSources": ["TASK-42", "NOTICE-5"]
}
```

Текущий prompt уже требует:

- определить новое это action item или продолжение существующей задачи;
- вернуть только JSON;
- различать `LINK_TO_TASK`, `UPDATE_TASK`, `NEW_TASK`, `IGNORE`, `REQUEST_CONFIRMATION`.

### Ограничения текущего prompt context

Сейчас в context передаются:

- `email`
- `email.recipients`
- `email.messageId`
- `email.conversationId`
- `email.inReplyTo`
- `normalizedSubject`
- `thread`
- `classification`
- `search`

Что все еще ограничено:

- нет richer thread metadata beyond `messageId` / `conversationId` / `inReplyTo`;
- нет task timeline context в search results как отдельного слоя;
- classification prompt template в уже установленной БД не переписывается автоматически, если пользователь ранее сохранял свою версию prompt-а.

## Изменения в API

### JavaMemoryService: поиск перед созданием задачи

Используется существующий `POST /api/search`.

Текущий mail-linking request:

```json
{
  "query": "<normalized subject + from + body snippet>",
  "layers": ["TASK", "NOTICE", "PEOPLE", "RISK", "INCIDENT", "KNOWLEDGE"],
  "mode": "QUICK",
  "limit": 8
}
```

Примечания:

- В коде используется `NOTICE`, а не `NOTE`.
- В коде используется `PEOPLE`, а не `PERSON`.
- Поиск по timeline events как отдельному слою пока не реализован.

### JavaMemoryService: pending-кандидат на связывание

Реализован подход через расширение обычной PENDING-задачи.

Текущий payload `POST /api/tasks/pending` фактически поддерживает поля:

```text
title
description
emailId
sender
priority
dueDate
pendingType
suggestedTaskId
agentConfidence
agentReason
sourceType
sourceSubject
sourceSender
proposedDescriptionAppend
```

Примечание: отдельного `source_id` поля нет; в качестве идентификатора письма используется существующее поле `emailId`.

### JavaMemoryService: применить связывание

Реализован endpoint:

```http
POST /api/tasks/pending/{pendingId}/link
```

Body:

```json
{
  "targetTaskId": 142,
  "appendSummary": true
}
```

Текущее действие endpoint:

- проверяет, что исходная задача находится в статусе `PENDING`;
- берет `targetTaskId` из body или fallback из `suggestedTaskId`;
- не позволяет линковать в `PENDING` и `ARCHIVED`;
- при `appendSummary=true` добавляет `proposedDescriptionAppend` в description target task;
- пишет `EMAIL_LINKED` в timeline целевой задачи;
- архивирует pending-кандидат.

## Изменения в схеме БД

MVP реализован через расширение таблицы `tasks`.

Фактически добавлены поля:

```text
pending_type
suggested_task_id
agent_confidence
agent_reason
source_type
source_subject
source_sender
proposed_description_append
linked_to_task_id
linked_at
```

Что пока не реализовано из исходной идеи:

```text
source_id
```

Если понадобится полноценный audit по факту связывания без чтения timeline, это можно будет добавить отдельной миграцией.

## UI изменения

В pending section UI уже есть поддержка карточек типа:

```text
LINK_TO_TASK
UPDATE_TASK
REQUEST_CONFIRMATION
```

Текущий UI flow:

### Для обычной новой задачи

```text
[Принять]
[Изменить]
[Отклонить]
```

### Для linking/update-кандидата

```text
[Создать новую задачу]
[Связать с TASK-<suggestedTaskId>] или [Обновить TASK-<suggestedTaskId>]
[Изменить]
[Отклонить]
```

Что еще не реализовано:

```text
отдельный dedicated search picker по всем задачам вместо ограниченного dropdown списка
```

После связывания pending-кандидат пропадает из pending queue, а существующая задача получает timeline event:

```text
EMAIL_LINKED — Связано из письма: <subject> от <sender>
```

## Зависимости

Этот CR логически опирается на:

```text
2026-06-29_CR-MEM-012-task-timeline-audit.md
2026-06-29_CR-MEM-012-global-search-tsvector-providers.md
```

Причина:

- linking должен отображаться как timeline event существующей задачи;
- linking использует Global Search по operational layers.

## Как тестировать

### Scenario 1: новое письмо создает новую задачу

1. Отправить письмо с новым уникальным содержанием.
2. Mail Agent классифицирует письмо как `REQUEST`.
3. Mail Agent вызывает Global Search.
4. Agent возвращает `NEW_TASK`.
5. Создается новая PENDING-задача.

### Scenario 2: ответ на письмо предлагает связать с задачей

1. Создать существующую задачу про релиз.
2. Отправить письмо `RE: Release` с похожим контекстом.
3. Global Search находит существующую задачу.
4. Agent возвращает `LINK_TO_TASK`.
5. UI показывает linking-кандидат.
6. Пользователь нажимает `Связать`.
7. Pending архивируется.
8. В timeline существующей задачи появляется `EMAIL_LINKED`.

### Scenario 3: письмо обновляет существующую задачу

1. Создать задачу с дедлайном.
2. Отправить письмо с новым сроком или новой договоренностью.
3. Agent возвращает `UPDATE_TASK`.
4. UI показывает предложенное обновление.
5. Пользователь нажимает `Обновить TASK-...`.
6. Description задачи получает append.
7. Timeline содержит `EMAIL_LINKED`.
8. Timeline description storage содержит `DESCRIPTION_UPDATED`.

### Scenario 4: noise не создает задачу

1. Отправить письмо, которое classification определяет как `REQUEST`, но linking считает шумом.
2. Agent возвращает `IGNORE`.
3. Итоговый тип меняется на `NOISE`.
4. Новая задача не создается.

### Scenario 5: fail-open при ошибке linking flow

1. Сломать `POST /api/search` или вернуть невалидный JSON из linking prompt.
2. Убедиться, что `MailLinkingService` не роняет обработку письма.
3. Письмо продолжает обрабатываться как исходный `REQUEST`.
4. Создается обычная PENDING-задача.

## Remaining Work

1. Прогнать новые E2E-сценарии на полном стенде через test-runner и зафиксировать результат в отчёте.
2. Расширить prompt context:
   - richer search candidates;
   - при необходимости timeline fragments.
3. Прогнать новый task search picker в полном browser E2E на живом стенде.

## Acceptance Criteria

- [x] Mail Agent перед созданием PENDING-задачи вызывает MemoryService Global Search.
- [x] Добавлен Mail Linking Prompt Builder.
- [x] AgentClient возвращает structured decision для mail linking.
- [x] Поддержаны решения `NEW_TASK`, `LINK_TO_TASK`, `UPDATE_TASK`, `IGNORE`, `REQUEST_CONFIRMATION`.
- [x] Pending UI умеет показывать linking-кандидата.
- [x] Пользователь может связать pending-кандидат с существующей задачей.
- [x] После связывания pending исчезает из очереди.
- [x] В timeline существующей задачи появляется `EMAIL_LINKED`.
- [x] Для `UPDATE_TASK` existing task получает description append.
- [x] Добавлены E2E-сценарии для duplicate email prevention и mail linking.
- [x] Добавлен UI-flow выбора альтернативной target task.
- [x] Dropdown заменен на dedicated task search picker поверх `/api/search`.
- [x] В prompt/context добавлены recipients и thread metadata.
- [x] Добавлены отдельные DB-поля `linked_to_task_id` / `linked_at`.
