# 2026-07-02_CR-MEM-025: Split Today into ToDo / Done sidebar tabs

**Дата:** 2026-07-02
**Статус:** Implemented
**Сервис:** MEM / JavaMemoryService
**Тип:** Enhancement / UI
**Supersedes:** CR-MEM-014 (`2026-06-29_CR-MEM-014-today-hide-done-filter.md`) — «No Done» toggle заменён отдельными вкладками.

## Итог реализации

- Пункт сайдбара «Today» заменён на два: **ToDo** (`/ui/today`) и **Done** (`/ui/today?status=DONE`).
- `TodayViewController.today()`: параметр `hideDone` убран, вместо него — вычисляемый `doneView = "DONE".equalsIgnoreCase(status)`. ToDo-вкладка всегда исключает `DONE`-задачи, Done-вкладка показывает только `DONE`.
- Toggle «No Done» и его hidden-поле удалены из `today.html`; заголовок карточки и `<title>` страницы теперь зависят от вкладки («ToDo задачи» / «Done задачи»).
- Ссылка «Сбросить» на Done-вкладке остаётся на Done-вкладке (не сбрасывает на ToDo).
- JS-логика подсветки активного пункта сайдбара (`fragments/layout.html`) переписана: пункты, ведущие на один и тот же `pathname` с разными query-параметрами, различаются по «различающим» query-ключам, вместо сравнения только по `pathname`.

## Проблема / Мотивация

Пользователь хотел явное разделение «текущей работы» и «сделанного» на уровне навигации, а не скрытого toggle внутри одной страницы.

## Решение

Переиспользован существующий механизм `status`-фильтра контроллера `/ui/today` (ранее уже умел показывать только `DONE` при `status=DONE` в обход `hideDone`) — новый функционал не потребовал новых endpoint'ов, только упрощение фильтрации и два разных пункта меню на один и тот же route.

## Изменения в API

`GET /ui/today` — убран query-параметр `hideDone` (ранее `boolean`, default `true`). Поведение теперь полностью определяется `status`.

## Изменения в схеме БД

Нет.

## Тесты

- `test_e2e/tests/14_today_hide_done_filter.spec.js` — переписан под вкладки ToDo/Done (5 сценариев).
- `test_e2e/tests/today-ui.spec.js` — обновлён ассерт заголовка страницы (`/ToDo/` вместо `/План дня/`).
- `test_e2e/tests/sidebar-navigation.spec.js` — без изменений, подтверждает отсутствие регрессий в сайдбаре (16/16 passed).

Итог прогона: 30/30 целевых тестов зелёные. Полный прогон test_e2e: 66/75 (9 падений в `capturebot-ui.spec.js`, предсуществующий баг роутинга capture, не связан с этим CR).
