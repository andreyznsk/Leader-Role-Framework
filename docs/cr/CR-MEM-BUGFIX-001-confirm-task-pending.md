# CR-MEM-BUGFIX-001: confirmTask — исследовать поведение задачи в PENDING после подтверждения

**Дата:** 2026-06-13
**Статус:** Draft
**Тип:** Investigation / BugFix
**Сервис:** JavaMemoryService
**Источник:** `test-runner/reports/TEST-REPORT-2026-06-13.md`, сценарий `JavaMemoryService/test_e2e/02_pending_task_flow.md` Step 4

---

## Наблюдение

В сценарии `02_pending_task_flow` Step 4 после вызова `POST /api/tasks/{id}/confirm` задача по-прежнему присутствует в ответе `GET /api/tasks/pending`.

**При этом:** сценарий `05_pending_task_flow` выполняет идентичный flow и проходит Step 7 (аналогичная проверка) корректно.

Расхождение между двумя сценариями означает, что сервис **в большинстве случаев работает правильно**, однако в сценарии `02_pending_task_flow` что-то приводит к ложному FAIL.

---

## Код — confirm()

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/service/TaskService.java`

```java
public Task confirm(Long id) {
    Task task = taskRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Task not found: " + id));
    LocalDate today = LocalDate.now();
    String planId = today.toString();

    Task confirmed = new Task(task.id(), planId, task.title(), task.description(),
            "TODO",               // ← статус меняется с PENDING на TODO
            task.priority(), today, task.source(), task.emailId(),
            task.sender());
    return taskRepository.save(confirmed);
}
```

```java
public List<Task> findPending() {
    return taskRepository.findByStatus("PENDING");  // ← фильтр по PENDING
}
```

Логика выглядит корректной: confirm обновляет статус на TODO, findPending ищет только PENDING.

---

## Гипотезы для расследования

### Гипотеза 1 — Изоляция данных (наиболее вероятная)

Сценарий `02_pending_task_flow` мог не выполнить cleanup от предыдущего прогона. В таблице `tasks` осталась задача с тем же email_id в статусе PENDING от прошлого запуска. После confirm целевой задачи — в списке PENDING видна именно эта стale-запись.

**Проверка:**
```bash
docker exec leader-postgres psql -U memory_user -d leader_framework \
  -c "SELECT id, status, email_id, created_at FROM tasks WHERE status='PENDING' ORDER BY id;"
```

### Гипотеза 2 — Неверный id при подтверждении

Сценарий использует `TASK_ID` из предыдущего шага. Если `TASK_ID` некорректен (null или id другой задачи), confirm возвращает 404/500, а реальная PENDING задача остаётся.

**Проверка:** логи шага confirm — был ли HTTP 200 или 4xx.

### Гипотеза 3 — Баг в taskRepository.save() при Record-типе

`Task` — Kotlin/Java record. При `save(confirmed)` Spring Data JPA может создавать **новую** запись вместо обновления, если определение `isNew()` некорректно (например, id null или стратегия GenerationType).

**Проверка:**
```bash
# После confirm — смотреть количество записей с одинаковым email_id:
docker exec leader-postgres psql -U memory_user -d leader_framework \
  -c "SELECT id, status, email_id FROM tasks WHERE email_id LIKE 'e2e%' ORDER BY id;"
```
Если есть две строки (одна PENDING, одна TODO) — это Гипотеза 3.

---

## Предлагаемые исправления

**Для Гипотезы 1 (изоляция данных):**
Добавить в секцию `## Preconditions` сценария `02_pending_task_flow.md` явный DELETE:
```bash
docker exec leader-postgres psql -U memory_user -d leader_framework \
  -c "DELETE FROM tasks WHERE email_id = 'e2e-pending-flow-test';"
```

**Для Гипотезы 3 (дублирование записи):**
Проверить `TaskRepository.java` — убедиться что entity использует `@GeneratedValue` корректно и `save()` делает UPDATE, а не INSERT при непустом id.

---

## Как воспроизвести

```bash
# 1. Запустить от чистой БД:
docker exec leader-postgres psql -U memory_user -d leader_framework \
  -c "DELETE FROM tasks WHERE email_id = 'e2e-test-pending';"

# 2. Создать PENDING задачу:
TASK_ID=$(curl -s -X POST http://localhost:8082/api/tasks/pending \
  -H "Content-Type: application/json" \
  -d '{"title":"CR Test","emailId":"e2e-test-pending","sender":"test@cr.ru","priority":"HIGH"}' \
  | jq -r '.id')
echo "Created: $TASK_ID"

# 3. Подтвердить:
curl -s -X POST http://localhost:8082/api/tasks/$TASK_ID/confirm | jq '{id, status}'

# 4. Проверить:
curl -s http://localhost:8082/api/tasks/pending \
  | jq '[.[] | select(.emailId == "e2e-test-pending")] | length'
# Ожидается: 0
```

---

## Acceptance Criteria

- `GET /api/tasks/pending` не содержит задачи с `emailId=e2e-test-pending` после `confirm`
- Сценарий `02_pending_task_flow.md` Step 4: PASS
- Сценарий `05_pending_task_flow.md` Step 7: остаётся PASS

---

## Приоритет

**HIGH** — если это реальный баг в TaskRepository.save(), то confirm в production создаёт дубликаты задач. Если это только проблема изоляции тестов — приоритет LOW.
