# 2026-06-29_CR-MAIL-005: Intelligent Mail Linking

**Дата:** 2026-06-29  
**Статус:** Draft  
**Сервис:** MAIL / MEM  
**Зависимости:** JavaMailAgent, JavaMemoryService, Global Search, common AgentClient, CR-MEM-012 Task Timeline Audit

## Проблема / Мотивация

Сейчас Mail Agent работает по простой модели:

```text
новое письмо -> классификация -> REQUEST -> новая PENDING-задача
```

Проблема: если кто-то отвечает в уже существующей email-цепочке через час, два или три, Mail Agent снова создает новую PENDING-задачу. В итоге появляются дубли задач, хотя по смыслу это продолжение уже существующей работы.

Нужно перейти от модели `письмо = новая задача` к модели:

```text
письмо = новый сигнал, который может быть новой задачей или продолжением существующей
```

## Решение

Перед созданием PENDING-задачи Mail Agent должен выполнить дополнительный reasoning-flow:

1. Получить новое письмо.
2. Сформировать поисковый запрос из темы, отправителя, recipients, normalized subject, ключевых фраз и текста письма.
3. Вызвать Global Search в JavaMemoryService.
4. Найти потенциально связанные объекты:
   - задачи;
   - notes;
   - notice;
   - people;
   - risks/incidents при необходимости;
   - knowledge/RAG;
   - будущие mail timeline events.
5. Передать письмо и найденный контекст в AgentClient через специальный prompt.
6. Агент должен вернуть решение: создать новую задачу, связать с существующей, обновить существующую или игнорировать.

## Новый pipeline

```text
New Email
  -> Mail Agent
  -> Search Query Builder
  -> MemoryService /api/search
  -> Global Search results
  -> Mail Linking Prompt Builder
  -> AgentClient
  -> Mail Linking Decision
  -> MemoryService action
```

## Решения агента

Агент должен вернуть строго структурированный результат.

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

Действие:

```text
создать pending-кандидат на связывание
или сразу связать при high confidence
```

После подтверждения пользователя:

```text
pending-кандидат исчезает из очереди
в timeline существующей задачи появляется EMAIL_LINKED
```

### UPDATE_TASK

Письмо относится к существующей задаче и содержит значимое обновление: новый срок, новое решение, новый риск, новая договоренность.

Действие:

```text
создать pending-кандидат на обновление задачи
```

После подтверждения пользователя:

```text
обновить описание/summary существующей задачи
добавить timeline event EMAIL_LINKED
добавить timeline event AGENT_SUMMARY_ADDED или DESCRIPTION_UPDATED
```

### IGNORE

Письмо не требует действий.

Действие:

```text
не создавать задачу
пометить письмо обработанным по текущим правилам Mail Agent
```

### REQUEST_CONFIRMATION

Агент не уверен.

Действие:

```text
создать PENDING-кандидат с вариантами действий в UI
```

## Prompt

Добавить новый prompt template в Mail Agent settings/control plane.

Примерная задача prompt-а:

```text
Ты анализируешь новое входящее письмо и найденный контекст LeaderOS.
Определи, является ли письмо новой задачей или продолжением существующей.

Верни строго JSON:
- decision
- confidence
- targetTaskId
- title
- summary
- reason
- proposedDescriptionAppend
- matchedSources
```

Prompt должен учитывать:

- тему письма;
- normalized subject без RE/FWD;
- sender/recipients;
- messageId/conversationId при наличии;
- тело письма;
- найденные Global Search results;
- существующие задачи и их статусы;
- timeline событий задачи, если доступен.

## Изменения в API

### MemoryService: поиск перед созданием задачи

Использовать существующий `POST /api/search`, но добавить или уточнить contract для mail-linking сценария.

Нужны слои:

```text
TASK
NOTE
PERSON
RISK
INCIDENT
KNOWLEDGE
```

После реализации CR-MEM-012 желательно добавить поиск по task timeline events.

### MemoryService: pending-кандидат на связывание

Нужно расширить модель pending task или добавить отдельный тип pending decision.

Минимальный вариант: расширить PENDING-задачу полями:

```text
pending_type: NEW_TASK | LINK_TO_TASK | UPDATE_TASK
suggested_task_id
agent_confidence
agent_reason
source_type = EMAIL
source_id = mail message id
source_subject
source_sender
proposed_description_append
```

UI должен показывать карточку решения:

```text
Письмо: RE: Release
Agent считает: похоже на TASK-142
Причина: совпадают тема релиза, дедлайн и участники

[Создать новую задачу]
[Связать с TASK-142]
[Отклонить]
```

### MemoryService: применить связывание

Добавить endpoint:

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

Действие:

- pending-кандидат больше не отображается в pending queue;
- существующая задача получает новый summary/description append при необходимости;
- в timeline существующей задачи добавляется `EMAIL_LINKED`;
- при обновлении описания добавляется `DESCRIPTION_UPDATED` или `AGENT_SUMMARY_ADDED`.

## Изменения в схеме БД

Вариант MVP: расширить таблицу pending/tasks полями для linking-кандидата.

Рекомендуемые поля:

```text
pending_type
suggested_task_id
agent_confidence
agent_reason
source_type
source_id
source_subject
source_sender
proposed_description_append
linked_to_task_id
linked_at
```

Если текущая модель не позволяет аккуратно расширить tasks, допустимо ввести отдельную таблицу `memory.pending_task_decisions`.

## UI изменения

В pending section UI добавить поддержку карточек типа `LINK_TO_TASK` и `UPDATE_TASK`.

Для обычной новой задачи оставить текущий flow:

```text
[Подтвердить]
[Отклонить]
```

Для linking-кандидата:

```text
[Создать новую задачу]
[Связать с найденной задачей]
[Выбрать другую задачу]
[Отклонить]
```

После связывания pending-кандидат исчезает из очереди, а существующая задача показывает в timeline:

```text
EMAIL_LINKED — Связано из письма RE: Release от Иванова
```

## Зависимости

Этот CR должен выполняться после:

```text
2026-06-29_CR-MEM-012-task-timeline-audit.md
```

Причина: результат linking должен отображаться как timeline event существующей задачи.

## Как тестировать

### Scenario 1: новое письмо создает новую задачу

1. Отправить письмо с новым уникальным содержанием.
2. Mail Agent вызывает Global Search.
3. Agent возвращает `NEW_TASK`.
4. Создается новая PENDING-задача.

### Scenario 2: ответ на письмо предлагает связать с задачей

1. Создать существующую задачу про релиз.
2. Отправить письмо `RE: Release` с похожим контекстом.
3. Global Search находит существующую задачу.
4. Agent возвращает `LINK_TO_TASK`.
5. UI показывает linking-кандидат.
6. Пользователь нажимает `Связать`.
7. Pending исчезает из очереди.
8. В timeline существующей задачи появляется `EMAIL_LINKED`.

### Scenario 3: письмо обновляет существующую задачу

1. Создать задачу с дедлайном.
2. Отправить письмо с новым сроком.
3. Agent возвращает `UPDATE_TASK`.
4. UI показывает предложенное обновление.
5. Пользователь подтверждает.
6. Описание/summary задачи обновляется.
7. Timeline содержит `EMAIL_LINKED` и update event.

### Scenario 4: noise не создает задачу

1. Отправить шумовое письмо.
2. Agent возвращает `IGNORE`.
3. Новая задача не создается.

## Acceptance Criteria

- [ ] Mail Agent перед созданием PENDING-задачи вызывает MemoryService Global Search.
- [ ] Добавлен Mail Linking Prompt Builder.
- [ ] AgentClient возвращает structured decision для mail linking.
- [ ] Поддержаны решения `NEW_TASK`, `LINK_TO_TASK`, `UPDATE_TASK`, `IGNORE`, `REQUEST_CONFIRMATION`.
- [ ] Pending UI умеет показывать linking-кандидата.
- [ ] Пользователь может связать pending-кандидат с существующей задачей.
- [ ] После связывания pending исчезает из очереди.
- [ ] В timeline существующей задачи появляется `EMAIL_LINKED`.
- [ ] Для `UPDATE_TASK` existing task получает summary/description append.
- [ ] Добавлены E2E-сценарии для duplicate email prevention и mail linking.
