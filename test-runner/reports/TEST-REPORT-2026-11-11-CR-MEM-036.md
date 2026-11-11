# TEST-REPORT-2026-11-11 — CR-MEM-036

**Дата:** 2026-11-11, Asia/Novosibirsk
**Scope:** JavaMemoryService — REST / MCP date range
**Профиль E2E:** ollama (явно выбран пользователем вместо local)
**Runner:** выполнен workflow test-runner/AGENT.md; отдельного SKILL.md e2e-test-runner в окружении нет, использована локальная инструкция .claude/agents/e2e-test-runner.md.

## Summary

| Проверка | Результат |
| --- | --- |
| Сборка Maven package | PASS |
| Целевые unit/integration/regression | PASS — 48 тестов, 0 failures/errors/skips |
| MCP read-only smoke, пользовательский экземпляр :8090 | PASS |
| REST + MCP SSE E2E с fixtures, отдельный экземпляр :18082 | PASS |
| 90-дневная выборка, 1000 возвращённых задач | PASS — 0.0339 s (<2 s) |
| Полный прогон JavaMemoryService до добавления 10 unit-проверок | PARTIAL — 165 тестов: 161 PASS, 4 ERROR (Jira stub port conflict) |

## Окружение и данные

Java 21.0.11, Spring Boot 3.5.14, PostgreSQL 15, Linux x86_64.
Рабочий экземпляр: http://localhost:8090, профиль ollama,
PostgreSQL leader_framework на 172.80.2.1:5432. Smoke read-only, operational данные не изменены.

Fixture E2E: отдельный процесс на http://localhost:18082, профиль ollama, Jira выключена,
отдельная БД cr036_e2e_20261111 на 172.80.2.1:5432 (schema memory).
Создано 1002 задачи: 1000 с dueDate на 90 датах 2091-01-01..2091-03-31,
две вне границ (2090-12-31 / 2091-04-01), статусы TODO/DONE.
Plan date всех задач 2090-01-01 — проверяется именно dueDate, а не plan date.
Пустая тестовая БД до запуска, существующие миграции применены без изменений.
Замер — один HTTP POST /api/tasks/range, включая чтение и JSON-декодирование ответа:
1000 задач из 1002, 0.0339 s. Это функциональный замер на указанном наборе, не нагрузочный benchmark.
После сценария все 1002 задачи архивированы через существующий DELETE API.

## Проверенные сценарии

- REST 200, массив существующего Task, обе границы включены, внешние даты исключены.
- Необязательный status; DONE возвращает ожидаемые задачи.
- HTTP 400 с code: invalid_date_range, invalid_date_format, range_too_large, invalid_status.
- Unit: пропущенные/пустые даты, строгий YYYY-MM-DD, невозможные даты, leap day,
  обратный диапазон, >365 дней, неизвестный/пустой статус.
- Integration: один день, ровно 365 дней, пустой результат, BLOCKED.
- MCP: реальный SSE initialize + notifications/initialized + tools/list;
  getTasksInRange требует from/to, status необязателен.
- MCP tools/call с данными и без status; фильтр DONE; прежний getTasks(date,status).
- Smoke на :8090 дополнительно проверил все шесть статусов, эквивалентность REST/MCP,
  четыре ошибки как MCP isError=true, getTasks с/без status. В выбранных датах результат пуст.

## Команды воспроизведения

```bash
mvn -o -pl JavaMemoryService -am test \
  -Dtest=TaskRangeValidationTest,TaskRangeTest,TaskControllerTest,TaskServiceTest,McpTaskToolTest,McpConnectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false
MEMORY_BASE_URL=http://localhost:8090 python3 JavaMemoryService/test_e2e/task-date-range.py --read-only
MEMORY_BASE_URL=http://localhost:18082 python3 JavaMemoryService/test_e2e/task-date-range.py
```

Fixture-команду выполнять только на отдельной тестовой БД.

## Ограничения полного прогона

`mvn -o -pl JavaMemoryService -am test` выполняет 165 тестов (до добавления TaskRangeValidationTest).
Три TaskJiraControllerTest и один TaskJiraUiTest не смогли запустить существующий
JiraStubServer: `java.net.BindException: Address already in use`, порт 19997.
На этом порту работает инфраструктурный Jira stub; его остановка не выполнялась.
161 тест прошёл; это не полный зелёный прогон. CR-MEM-036 target suite: все 48 прошли.

Первоначальные sandbox-запуски не могли self-attach Mockito/Byte Buddy; успешные проверки
выполнены вне sandbox с разрешением пользователя. Первоначальный временный Docker PostgreSQL
оказался недоступен из Java; успешный E2E использует отдельную БД на доступном сервере.

## Workflow

Реализация готова к PR/review (Refs #92). RFC/ARCHITECTURE и реестр не актуализированы:
по workflow CR это следующий шаг после проверки/одобрения пользователя.
Статус Implemented и закрытие Issue #92 — только после merge; автоматический merge запрещён.
