# CR-MEM-BUGFIX-003: GET /api/tasks возвращает задачи со статусом DELETED

**Дата:** 2026-06-11  
**Статус:** Approved  
**Тип:** bugfix  
**Сервис:** JavaMemoryService  
**Severity:** HIGH  
**Источник:** TEST-REPORT-2026-06-11-memory v2 / 05_pending_task_flow / Step 3

---

## Проблема

`GET /api/tasks?date=2026-06-11` возвращает **все** задачи за дату,
включая `status = DELETED`.

```bash
curl -s "http://localhost:8082/api/tasks?date=2026-06-11" | jq 'length'
# → 19  (18 DELETED + 1 реальная)
```

**Последствия:**
- `/ui/today` показывает удалённые задачи пользователю
- E2E тесты нестабильны при повторных прогонах — старые DELETED задачи
  с тем же `emailId` дают ложный `count=1`
- `getContext` через MCP потенциально тоже тянет мусор

**Работает корректно:** `?status=TODO` фильтрует правильно — проблема
именно в запросе без явного параметра `status`.

---

## Решение

Фильтровать `DELETED` задачи на уровне репозитория или сервиса.

**Правило:** `GET /api/tasks?date=` без параметра `status` должен возвращать
все задачи **кроме** `DELETED`. С параметром `?status=DELETED` — можно явно
запросить удалённые (для истории/аудита).

---

## Изменения в коде

### Вариант A — в Repository (предпочтительно)

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/repository/TaskRepository.java`

```java
// Добавить метод с исключением DELETED:
List<Task> findByPlanIdAndStatusNot(Long planId, String status);

// Использовать вместо findByPlanId:
// taskRepository.findByPlanIdAndStatusNot(planId, "DELETED")
```

### Вариант B — в Service (если нет возможности менять Repository)

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/service/TaskService.java`

```java
public List<Task> getTasksForDate(LocalDate date, String statusFilter) {
    DailyPlan plan = dailyPlanRepository.findByPlanDate(date)
        .orElse(null);
    if (plan == null) return List.of();

    List<Task> tasks = taskRepository.findByPlanId(plan.id());

    // Если явный фильтр — применить его
    if (statusFilter != null && !statusFilter.isBlank()) {
        return tasks.stream()
            .filter(t -> t.status().equals(statusFilter))
            .toList();
    }

    // Без фильтра — исключить DELETED
    return tasks.stream()
        .filter(t -> !"DELETED".equals(t.status()))
        .toList();
}
```

### Также проверить ContextService

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/service/ContextService.java`

Убедиться что `buildContext()` тоже не тянет DELETED задачи:

```java
// В buildContext() при загрузке задач дня:
List<Task> todayTasks = taskRepository.findByPlanIdAndStatusNot(plan.id(), "DELETED");
// или аналогичный фильтр
```

---

## Как проверить

```bash
# Очистить тестовые данные (если нужно)
# Создать одну задачу, удалить её, создать другую

TODAY=$(date +%Y-%m-%d)

# Создать и удалить
ID1=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Удалённая\",\"date\":\"$TODAY\",\"priority\":\"LOW\",\"source\":\"MANUAL\"}" \
  | jq -r '.id')
curl -s -X POST "http://localhost:8082/api/tasks/$ID1/delete" > /dev/null

# Создать живую
ID2=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Живая\",\"date\":\"$TODAY\",\"priority\":\"NORMAL\",\"source\":\"MANUAL\"}" \
  | jq -r '.id')

# Проверить — должна быть только 1 живая задача
curl -s "http://localhost:8082/api/tasks?date=$TODAY" | jq 'length'
# Ожидается: 1 (не 2)

curl -s "http://localhost:8082/api/tasks?date=$TODAY" | jq '[.[] | select(.status=="DELETED")] | length'
# Ожидается: 0

# Cleanup
curl -s -X POST "http://localhost:8082/api/tasks/$ID2/delete" > /dev/null
```

**Сценарий для проверки после фикса:** `05_pending_task_flow.md` — повторный прогон
