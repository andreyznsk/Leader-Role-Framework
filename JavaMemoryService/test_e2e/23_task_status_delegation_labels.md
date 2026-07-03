# 23. Task statuses, delegation and labels

## Goal

Проверить новые статусы `RESEARCH` и `DELEGATED`, обязательность `assignedPersonId` для делегирования, работу task labels и фильтрацию по labels.

## Steps

1. Проверить healthcheck JavaMemoryService.
2. Создать тестового человека через `/api/people`.
3. Создать labels `Release` и `Architecture` через `/api/task-labels`.
4. Создать задачу со статусом `RESEARCH`.
5. Попробовать создать или обновить задачу в `DELEGATED` без `assignedPersonId`.
6. Убедиться, что backend возвращает validation error.
7. Перевести задачу в `DELEGATED` с `assignedPersonId` тестового человека.
8. Назначить задаче оба labels.
9. Проверить `GET /api/tasks?date=YYYY-MM-DD&labelId=<releaseId>`.
10. Открыть `/ui/tasks/{id}/edit` и убедиться, что:
    - статус `DELEGATED` выбран;
    - исполнитель отображается в `Assigned to`;
    - labels отмечены чекбоксами.
11. Открыть `/ui/today`, включить фильтр по одному label и проверить, что задача остается в списке.
12. Архивировать тестовые labels и cleanup тестовых задач/people.

## Expected

- `RESEARCH` сохраняется без исполнителя.
- `DELEGATED` без исполнителя не сохраняется.
- У делегированной задачи отображается assigned person.
- Labels сохраняются, показываются в списке и фильтруются по ANY semantics.
