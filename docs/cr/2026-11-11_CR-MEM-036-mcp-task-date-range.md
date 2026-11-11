# CR-MEM-036: MCP date-range query endpoints

**Дата:** 2026-11-11  
**Статус:** Draft  
**Сервис:** MEM — JavaMemoryService  
**Тип:** Feature / MCP API  
**Приоритет:** MEDIUM  
**Consumer:** GigaCode AI agents  
**GitHub Issue:** [#92](https://github.com/andreyznsk/Leader-Role-Framework/issues/92)

## Проблема / Мотивация

Текущий MCP-инструмент `getTasks(date, status)` принимает одну дату. Для отчётов за квартал агенту требуется много последовательных вызовов, создающих лишние round-trips и затраты токенов. Нужна единая выборка задач по диапазону дат и статусу.

## Решение

Реализовать MCP-инструмент `getTasksInRange(from, to, status?)`, позволяющий получить задачи одним вызовом. Диапазон включительный, фильтрация по `dueDate`. Параметр `status` необязателен. Существующий `getTasks` сохраняется без изменений.

### Контракт MCP

| Поле | Формат | Обязательность | Семантика |
| --- | --- | --- | --- |
| `from` | `YYYY-MM-DD` | Да | Первая дата включительно |
| `to` | `YYYY-MM-DD` | Да | Последняя дата включительно |
| `status` | string | Нет | Фильтр статуса; отсутствие означает все статусы |

Статусы из Issue #92: `TODO`, `RESEARCH`, `IN_PROGRESS`, `DELEGATED`, `DONE`, `BLOCKED`. Перед реализацией проверить актуальный доменный enum: в загруженной ARCHITECTURE.md `BLOCKED` не перечислен. Не добавлять новый доменный статус без отдельного решения.

## Изменения в API

```http
POST /api/tasks/range
Content-Type: application/json

{"from":"2026-10-01","to":"2026-12-31","status":"DONE"}
```

Ответ: HTTP 200, массив существующих DTO задач, как у `getTasks` (без изменения сериализации).

Валидация:

| Условие | HTTP | Код ошибки |
| --- | --- | --- |
| `from > to` | 400 | `invalid_date_range` |
| Невалидный формат `from`/`to` | 400 | `invalid_date_format` |
| Разница `to - from` более 365 дней | 400 | `range_too_large` |
| Неизвестный статус | 400 | `invalid_status` |

Не полагаться только на `@DateTimeFormat` для JSON body; обеспечить проверяемую обработку ошибок. Даты включаются по границам `from <= dueDate <= to`.

## Изменения в JavaMemoryService

1. Добавить REST API в существующий TaskController с переиспользованием DTO и централизованной обработки ошибок.
2. Добавить `TaskService.findByRange(LocalDate from, LocalDate to, String status)` либо эквивалент без дублирования логики.
3. Добавить эффективный запрос в TaskRepository (фильтр `dueDate`, опциональный `status`) без N+1 и без выборки всех записей в память.
4. Зарегистрировать `getTasksInRange` в существующей MCP-конфигурации, подтвердить обнаружение через `tools/list` и исполнение вызова.
5. Обеспечить совместимость с `getTasks(date, status)`; не менять его контракт.

## Изменения в схеме БД

Не предполагаются. Существующие миграции в `*/db/migration` изменять запрещено.

## Зависимости

- JavaMemoryService REST / MCP / Spring AI.
- Существующая модель задач и PostgreSQL `memory`.
- `test-runner/AGENT.md` и навык `e2e-test-runner`.

## Как тестировать

- Unit tests: обе границы включены, отсутствие статуса, конкретный статус, пустой результат, некорректные параметры.
- Integration tests: `POST /api/tasks/range`, HTTP 200/400 и коды ошибок.
- MCP E2E: `getTasksInRange` обнаруживается и возвращает ожидаемые задачи.
- Regression: прежний `getTasks` работает без изменений.
- Performance: 90-дневная выборка менее 2 секунд на оговорённом тестовом наборе данных, с фиксацией объёма и окружения.
- Запустить `e2e-test-runner`, сохранить отчёт в `test-runner/reports/`.

## Acceptance Criteria

- [x] `POST /api/tasks/range` возвращает HTTP 200 и массив задач.
- [x] `dueDate >= from AND dueDate <= to`.
- [x] Необязательный `status` фильтрует результаты.
- [x] Четыре случая валидации возвращают HTTP 400 с оговорёнными кодами.
- [x] 90-дневный запрос менее 2 секунд в документированном тестовом окружении.
- [x] `getTasksInRange` доступен через MCP и успешно вызывается.
- [x] Старый `getTasks(date, status)` не изменён.
- [ ] Unit, integration, E2E и regression tests проходят.

## Workflow выполнения

Issue #92 — задача локальному AI-агенту. После реализации агент создаёт PR с `Refs #92` и передаёт на ревью пользователя. После проверки/одобрения актуализировать RFC JavaMemoryService и ARCHITECTURE.md (если изменились публичные контракты), обновить реестр CR. После merge перевести CR в `Implemented` и закрыть Issue #92. Не выполнять merge автоматически.

## Результат реализации (на ревью)

Реализованы REST и MCP, общий сервис валидации, SQL-фильтрация по dueDate и пакетная загрузка связей.
48 целевых тестов и REST/MCP SSE E2E прошли. 90-дневный запрос вернул 1000 задач за 0.0339 s.
Полный прогон ограничен четырьмя Jira-тестами: порт stub 19997 занят (`BindException`).
Подробности: [отчёт](../../test-runner/reports/TEST-REPORT-2026-11-11-CR-MEM-036.md).
Статус CR сохранён до review/merge согласно workflow.
