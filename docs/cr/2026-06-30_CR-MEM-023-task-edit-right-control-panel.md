# 2026-06-30_CR-MEM-023: Task Edit Right Control Panel

**Дата:** 2026-06-30  
**Статус:** Draft  
**Сервис:** MEM / JavaMemoryService UI  
**Тип:** UI / Task Edit  
**Ветка:** `feature/MEM-023-2026-06-30`

## Проблема / Мотивация

На экране редактирования задачи управление задачей сейчас смешано с основным содержимым формы. Из-за этого:

- пользователь хуже отделяет содержание задачи от управляющих атрибутов;
- статус, приоритет и дедлайн не воспринимаются как единый control panel;
- timeline визуально конкурирует с основным описанием задачи;
- не хватает удобного единого блока действий: сохранить, сохранить и закрыть, архивировать.

Нужно сделать редактирование задачи более похожим на рабочую карточку: основное содержание слева, управление и timeline справа.

## Решение

В edit-flow каждой задачи перенести управление задачей в правый столбец.

Целевая структура страницы:

```text
┌──────────────────────────────────────┬────────────────────────────┐
│ Основная область редактирования       │ Task Control Panel          │
│                                      │ 1. Priority                 │
│ - Title                              │ 2. Deadline                 │
│ - Description / details              │ 3. Status select            │
│ - Notes / body                       │ 4. Action buttons           │
│                                      │    💾 Save                  │
│                                      │    ✅ Save and close         │
│                                      │    🗄 Archive / red button   │
│                                      │                            │
│                                      │ Timeline                    │
│                                      │ - created                   │
│                                      │ - status changed            │
│                                      │ - archived                  │
└──────────────────────────────────────┴────────────────────────────┘
```

## Требования к правому столбцу

Порядок элементов строго такой:

1. **Приоритет**
   - текущий priority задачи;
   - editable control, если сейчас поле уже редактируется на странице;
   - использовать существующие значения priority проекта.

2. **Дедлайн**
   - дата дедлайна / dueDate;
   - editable date input, если сейчас дедлайн уже редактируется на странице;
   - не ломать существующую логику Today / Calendar filtering.

3. **Статус**
   - сделать выпадающий список;
   - список должен содержать все возможные статусы задачи:
     - `PENDING`
     - `TODO`
     - `IN_PROGRESS`
     - `DONE`
     - `ARCHIVED`
     - `DELETED` только если enum/legacy статус всё ещё реально поддерживается backend-ом; если он не используется в UI, не показывать как основной пользовательский вариант.
   - изменение статуса должно использовать существующий backend flow, если он уже есть (`PATCH /api/tasks/{id}/status` или текущий save endpoint).

4. **Кнопки действий с иконками**
   - `Save` — сохранить изменения и остаться на странице;
   - `Save and close` — сохранить изменения и вернуться на предыдущий список / Today;
   - `Archive` — архивировать задачу;
   - `Archive` должна быть красной / destructive action;
   - все кнопки должны иметь иконки и понятные labels.

5. **Timeline ниже**
   - timeline отображается в этом же правом столбце ниже блока управления;
   - если timeline уже реализован, перенести его визуально в правый столбец;
   - если timeline пустой, показать нормальный empty state;
   - не ломать существующий task timeline audit flow.

## UX-детали

- Правый столбец должен быть sticky на desktop, если это не ломает layout.
- Ширина правого столбца ориентировочно `300–380px`.
- На узком экране layout должен становиться одноколоночным:
  - сначала основная форма;
  - ниже control panel;
  - ниже timeline.
- Destructive action `Archive` должна визуально отличаться от обычных save-действий.
- Желательно визуально отделить action buttons от полей управления.

## Техническая реализация

Рекомендуемый подход:

1. Найти шаблон редактирования задачи:
   - вероятно `JavaMemoryService/src/main/resources/templates/task-edit.html`;
   - либо текущий шаблон/fragment, который обслуживает `/ui/tasks/{id}/edit`.
2. Проверить текущий contract edit-flow:
   - как сохраняется title/description/priority/dueDate;
   - как меняется статус;
   - как выполняется archive.
3. Перестроить HTML layout на two-column editor:
   - left: content editor;
   - right: task control panel.
4. Вынести control panel в отдельный fragment, если это упростит дальнейшее развитие.
5. Добавить status select со всеми допустимыми task statuses.
6. Подключить кнопки:
   - Save;
   - Save and close;
   - Archive.
7. Перенести timeline под control block в правую колонку.
8. Обновить CSS для responsive layout.
9. При необходимости обновить JS обработчики формы.

## Изменения в API

Новые API не требуются.

Разрешено использовать существующие endpoint-ы:

- `PUT` / текущий endpoint сохранения задачи;
- `PATCH /api/tasks/{id}/status`;
- существующий archive endpoint / status transition to `ARCHIVED`.

Если существующего endpoint-а для `Save and close` нет, реализовать это как UI behavior:

```text
save success → redirect back to /ui/today or previous returnUrl
```

## Изменения в схеме БД

Не требуются.

## Зависимости

- JavaMemoryService task edit UI.
- Существующие task statuses.
- Существующий task timeline / audit flow.
- Существующие task update / archive endpoint-ы.

## Acceptance Criteria

- [ ] На странице редактирования задачи основная форма находится слева, управление задачей — справа.
- [ ] В правом столбце элементы идут строго в порядке: Priority → Deadline → Status → Buttons → Timeline.
- [ ] Status реализован как выпадающий список.
- [ ] Status dropdown содержит все актуальные возможные статусы задачи.
- [ ] Кнопка `Save` сохраняет изменения и оставляет пользователя на странице редактирования.
- [ ] Кнопка `Save and close` сохраняет изменения и возвращает пользователя на Today / previous list.
- [ ] Кнопка `Archive` архивирует задачу и визуально оформлена как destructive red action.
- [ ] Все action buttons имеют иконки и читаемые labels.
- [ ] Timeline отображается ниже блока управления в правом столбце.
- [ ] Timeline empty state отображается корректно, если событий нет.
- [ ] На узком экране layout не ломается и становится одноколоночным.
- [ ] Существующий backend API и схема БД не меняются.
- [ ] Существующие сценарии task edit и task timeline/archive flow продолжают проходить.

## Как тестировать

### Manual UI smoke

1. Запустить JavaMemoryService локально.
2. Создать тестовую задачу или открыть существующую.
3. Перейти на `/ui/tasks/{id}/edit`.
4. Проверить layout:
   - слева основная форма;
   - справа control panel.
5. Проверить порядок справа:
   - Priority;
   - Deadline;
   - Status select;
   - Save / Save and close / Archive;
   - Timeline.
6. Изменить priority и deadline → нажать Save → остаться на edit page.
7. Изменить status через dropdown → сохранить → проверить, что статус обновился.
8. Нажать Save and close → проверить возврат на Today / список.
9. Нажать Archive → проверить, что задача ушла в `ARCHIVED`.
10. Проверить, что timeline показывает события изменения / архивирования.
11. Проверить адаптивность на узком экране.

### E2E / smoke

Обновить сценарии:

- `JavaMemoryService/test_e2e/04_edit_task.md`
- `JavaMemoryService/test_e2e/17_task_timeline_archive_flow.md`
- при необходимости `JavaMemoryService/test_e2e/11_ui_smoke.md`

Проверить наличие UI markers, например:

```html
<aside data-testid="task-control-panel">
<select data-testid="task-status-select">
<section data-testid="task-timeline">
```

## После реализации

После подтверждения пользователя перевести этот CR в статус `DONE` / `Implemented` и обновить реестр `docs/cr/REGISTRY.md`.
