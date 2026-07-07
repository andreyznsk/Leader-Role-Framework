# 2026-07-03_CR-MEM-032: Task statuses, delegation and configurable labels

**Дата:** 2026-07-03  
**Статус:** DONE  
**Сервис:** MEM / JavaMemoryService  
**Тип:** enhancement  
**Связанный Issue:** #74  

---

## Проблема / Мотивация

Текущий task-management MVP покрывает базовый жизненный цикл задачи, но для реальной работы техлида не хватает трёх возможностей:

1. Отдельно фиксировать задачи, которые находятся на стадии исследования, когда ещё непонятно, как именно их выполнять.
2. Делегировать задачу конкретному человеку из раздела People, не теряя связь между задачей и членом команды.
3. Помечать задачи пользовательскими лейблами и быстро фильтровать общий список задач по этим лейблам.

Без этих возможностей список задач остаётся плоским: нельзя быстро отделить release-задачи от research/architecture/debt, а делегированные задачи не имеют формального владельца внутри LeaderOS.

---

## Решение

Расширить JavaMemoryService Task Management:

- добавить дополнительные статусы задач `RESEARCH` и `DELEGATED`;
- при переводе задачи в `DELEGATED` требовать привязку к человеку из People;
- добавить справочник пользовательских task labels;
- вынести настройку labels в Control Plane UI;
- дать возможность выбирать 1..N labels в карточке задачи;
- добавить фильтр по labels на общей странице задач.

### Важное продуктово-техническое решение

Для MVP принимаем `DELEGATED` как отдельный статус, потому что пользователь явно хочет видеть делегированные задачи как отдельную стадию.

При этом реализация должна быть подготовлена так, чтобы в будущем можно было отделить workflow status от assignment state без полной переделки схемы.

---

## Функциональные требования

### 1. Новые статусы задач

Добавить в enum статусов задачи:

```text
RESEARCH
DELEGATED
```

Ожидаемый workflow:

```text
PENDING -> TODO -> RESEARCH -> IN_PROGRESS -> DELEGATED -> DONE -> ARCHIVED
```

Требования:

- существующие статусы не ломать;
- старые задачи должны продолжить отображаться;
- фильтры по статусам должны учитывать новые значения;
- UI должен позволять выбрать новые статусы там, где сейчас выбирается статус задачи.

### 2. Статус RESEARCH

`RESEARCH` используется, когда задача требует анализа до выполнения:

- изучить документацию;
- разобраться в проблеме;
- собрать вводные;
- оценить варианты решения;
- подготовить технический подход.

Для `RESEARCH` не требуется обязательный исполнитель.

### 3. Статус DELEGATED

`DELEGATED` используется, когда задача передана другому человеку.

При выборе `DELEGATED` обязательно указать `assignedPersonId` — ссылку на существующую запись из People.

Правила:

- нельзя сохранить задачу со статусом `DELEGATED` без выбранного человека;
- выбранный человек должен существовать и не быть удалённым;
- если статус меняется с `DELEGATED` на другой, `assignedPersonId` можно оставить для истории/контекста, но UI должен позволять очистить исполнителя;
- в списке задач рядом с такой задачей показывать исполнителя.

### 4. Привязка задачи к People

Добавить в задачу поле:

```text
assignedPersonId
```

UI:

- в карточке задачи добавить селектор `Assigned to` / `Responsible`;
- источником значений является раздел People;
- для `DELEGATED` поле обязательно;
- для остальных статусов поле опционально.

На общей странице задач отображать:

```text
DELEGATED -> Иван Иванов
```

или компактный бейдж:

```text
👤 Иван Иванов
```

### 5. Пользовательские labels

Добавить справочник task labels.

Label — это пользовательская настройка, а не enum в коде.

Примеры labels:

```text
Backend
Frontend
Release
Architecture
Incident
Refactoring
Monitoring
Meeting
Research
Debt
Customer
```

MVP поля label:

```text
id
name
color
createdAt
updatedAt
archived
```

Цвет можно реализовать как optional поле. Если в UI цвет пока не нужен, backend всё равно может хранить `color` nullable для будущего развития.

### 6. Labels в Control Plane

В Control Plane добавить раздел управления task labels.

Варианты размещения:

```text
/ui/settings -> Task Labels
```

или отдельный блок внутри текущей Settings / Control Plane страницы.

Функции MVP:

- создать label;
- переименовать label;
- удалить/архивировать label;
- просмотреть список labels.

Предпочтительно не делать hard delete, если label уже используется задачами. Для MVP использовать soft delete / archived.

### 7. Labels в карточке задачи

В форме создания/редактирования задачи добавить блок:

```text
Labels
[x] Backend
[x] Release
[ ] Incident
[ ] Research
```

Требования:

- задача может иметь 0..N labels;
- пользователь может выбрать несколько labels чекбоксами;
- выбранные labels сохраняются вместе с задачей;
- на карточке/в списке задач labels отображаются бейджами.

### 8. Фильтрация задач по labels

На общей странице задач добавить фильтр по labels.

MVP режим фильтрации:

```text
ANY
```

То есть задача попадает в результат, если имеет хотя бы один из выбранных labels.

Пример:

```text
Backend OR Release
```

`ALL` режим не входит в MVP, но реализацию желательно не закрывать для будущего расширения.

---

## Изменения в API

### Task API

Расширить request/response модели задачи:

```json
{
  "status": "RESEARCH",
  "assignedPersonId": 123,
  "labelIds": [1, 2, 5]
}
```

Для response желательно отдавать не только ids, но и удобные display objects:

```json
{
  "assignedPerson": {
    "id": 123,
    "name": "Иван Иванов"
  },
  "labels": [
    { "id": 1, "name": "Backend", "color": null },
    { "id": 2, "name": "Release", "color": null }
  ]
}
```

### Task labels API

Добавить REST endpoints:

```http
GET    /api/task-labels
POST   /api/task-labels
PUT    /api/task-labels/{id}
DELETE /api/task-labels/{id}
```

Поведение:

- `GET /api/task-labels` возвращает только неархивные labels по умолчанию;
- `POST` создаёт новый label;
- `PUT` обновляет name/color/archived;
- `DELETE` архивирует label, если он используется, или выполняет soft delete всегда.

### Task list filters

Расширить endpoint списка задач:

```http
GET /api/tasks?date=YYYY-MM-DD&labelIds=1,2,5
```

или повторяемые query params:

```http
GET /api/tasks?date=YYYY-MM-DD&labelId=1&labelId=2
```

MVP semantics:

```text
ANY selected label
```

---

## Изменения в схеме БД

> Важно: существующие миграции в `*/db/migration` не изменять. Добавить только новую Flyway migration.

### Новые таблицы

```sql
memory.task_labels
```

Поля MVP:

```text
id BIGSERIAL PRIMARY KEY
name VARCHAR NOT NULL UNIQUE
color VARCHAR NULL
archived BOOLEAN NOT NULL DEFAULT FALSE
created_at TIMESTAMP NOT NULL
updated_at TIMESTAMP NOT NULL
```

```sql
memory.task_label_mapping
```

Поля MVP:

```text
task_id BIGINT NOT NULL
label_id BIGINT NOT NULL
PRIMARY KEY (task_id, label_id)
```

Добавить foreign keys на tasks и task_labels согласно текущей схеме проекта.

### Изменение task table

Добавить nullable поле:

```text
assigned_person_id BIGINT NULL
```

FK на People table согласно текущей схеме проекта.

### Статусы

Если статусы хранятся как enum в Java и строка в БД — добавить значения только в Java enum/валидацию.
Если в БД есть check constraint по статусам — добавить новую миграцию, расширяющую constraint.

---

## UI изменения

### Task edit / create

Добавить:

- selector `Assigned to` из People;
- блок `Labels` с чекбоксами;
- новые значения статуса `Research` и `Delegated`.

Validation:

- если status = `DELEGATED` и assigned person пустой — показать ошибку и не сохранять.

### Task list / Today / общая страница задач

Добавить:

- отображение labels бейджами;
- отображение assigned person;
- фильтр по labels;
- новые статусы в status filter/tabs, если такие фильтры есть.

### Settings / Control Plane

Добавить управление labels:

- список labels;
- создание label;
- переименование label;
- архивирование label.

---

## Зависимости

- JavaMemoryService Task Management;
- People module в JavaMemoryService;
- текущий Control Plane / Settings UI;
- текущий UI списка задач и карточки редактирования задачи.

---

## Acceptance Criteria

1. В системе доступны новые статусы задач `RESEARCH` и `DELEGATED`.
2. Задачу можно перевести в `RESEARCH` без дополнительных обязательных полей.
3. Задачу нельзя перевести в `DELEGATED` без выбора человека из People.
4. Задача со статусом `DELEGATED` отображает выбранного исполнителя.
5. В Control Plane доступно управление task labels.
6. Пользователь может создать label в Control Plane.
7. Пользователь может выбрать 1..N labels в задаче.
8. Labels отображаются в списке задач.
9. Общая страница задач поддерживает фильтрацию по labels в режиме ANY.
10. API задачи возвращает assigned person и labels.
11. Существующие задачи без labels и assignee продолжают работать.
12. Добавлен E2E сценарий для статусов, делегирования, labels и фильтрации.
13. Существующие Flyway migrations не изменены; добавлена только новая migration.

---

## Как тестировать

### Backend smoke

1. Создать label `Release` через `/api/task-labels`.
2. Создать label `Architecture` через `/api/task-labels`.
3. Создать задачу со статусом `RESEARCH`.
4. Проверить, что задача сохранилась и отображается.
5. Попробовать перевести задачу в `DELEGATED` без `assignedPersonId`.
6. Ожидание: backend возвращает validation error.
7. Создать/найти человека в People.
8. Перевести задачу в `DELEGATED` с `assignedPersonId`.
9. Назначить задаче labels `Release` и `Architecture`.
10. Проверить фильтр `/api/tasks?labelIds=<releaseId>`.

### UI smoke

1. Открыть `/ui/settings` и создать label.
2. Открыть задачу на редактирование.
3. Выбрать status `Research`.
4. Выбрать status `Delegated` и assigned person.
5. Отметить несколько labels.
6. Сохранить.
7. Вернуться на список задач.
8. Проверить отображение labels и исполнителя.
9. Включить фильтр по label и проверить список.

### E2E сценарий

Добавить файл:

```text
JavaMemoryService/test_e2e/XX_task_status_delegation_labels.md
```

Сценарий должен проверить:

- healthcheck MemoryService;
- создание labels;
- создание/использование person;
- `RESEARCH` статус;
- validation error для `DELEGATED` без person;
- успешный `DELEGATED` с person;
- назначение labels;
- фильтрацию задач по labels;
- cleanup тестовых данных.

---

## Документация после реализации

После реализации и одобрения пользователя:

1. Обновить `ARCHITECTURE.md` — раздел JavaMemoryService / статусы задач / endpoints / таблицы.
2. Обновить RFC MemoryService, если он есть в репозитории.
3. Обновить `docs/cr/REGISTRY.md`: статус CR-MEM-032 -> Implemented.
4. Создать/обновить E2E test report.

---

## После подтверждения пользователя перевести этот CR в Статус: DONE

После подтверждения пользователя перевести этот CR в Статус: DONE и обновить реестр `docs/cr/REGISTRY.md`.
