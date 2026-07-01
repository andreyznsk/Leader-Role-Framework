# 2026-07-02_CR-MEM-026: Intake Gateway — bulk checkbox selection and mass apply/reject

**Дата:** 2026-07-02
**Статус:** Implemented
**Сервис:** MEM / JavaMemoryService
**Тип:** Enhancement / UI

## Итог реализации

На `/ui/intake` добавлено:

- чекбокс на каждой карточке intake-элемента + чекбокс «Выбрать все» (`#intake-select-all`, с indeterminate-состоянием при частичном выборе);
- счётчик выбранных элементов (`#intake-bulk-count`);
- кнопка **«Подтвердить выбранные»** (`#intake-bulk-apply-btn`) — массовый apply с текущим выбранным маршрутом (`finalRoute`/`finalPayload`) каждого элемента, как если бы нажали «Apply» у каждого по отдельности;
- кнопка **«Отклонить выбранные»** (`#intake-bulk-reject-btn`) — массовый reject (`reason: 'noise'`), эквивалент одиночного «Reject» для каждого выбранного;
- обе кнопки задизейблены, пока ничего не выбрано; оба действия требуют `window.confirm(...)`.

## Проблема / Мотивация

Пользователю требовалось разбирать intake-очередь пачками, а не по одному элементу.

## Решение

Реализовано полностью на фронтенде (`templates/intake.html`): bulk-действия — это цикл по выбранным id с последовательными вызовами уже существующих одиночных endpoint'ов. **Backend не менялся** — новых bulk-endpoint'ов не создавалось, поскольку семантика была согласована с пользователем как «то же самое, что одиночные Apply/Reject, только для нескольких элементов сразу»:

- bulk-подтверждение = `Apply` с текущим `suggestedRoute`/`finalRoute` элемента (без доп. ввода);
- bulk-удаление = `Reject` (`reason: 'noise'`) — физического удаления записей из БД не вводилось, endpoint'а `DELETE` для intake-элементов по-прежнему нет.

## Изменения в API

Нет — переиспользованы `POST /api/intake/{id}/apply` и `POST /api/intake/{id}/reject`.

## Изменения в схеме БД

Нет.

## Тесты

`test_e2e/tests/intake-bulk-actions.spec.js` — 5 сценариев (bulk reject, bulk apply, отмена confirm, disabled-состояние кнопок без выбора, «выбрать все»). Плюс `sidebar-navigation.spec.js` для регрессии сайдбара. Итог: 21/21 passed.
