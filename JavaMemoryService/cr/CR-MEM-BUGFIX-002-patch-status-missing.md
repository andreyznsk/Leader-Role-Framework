# CR-MEM-BUGFIX-002: Отсутствует PATCH /api/tasks/{id}/status

**Дата:** 2026-06-11  
**Статус:** Approved  
**Тип:** bugfix  
**Сервис:** JavaMemoryService  
**Источник:** TEST-REPORT-2026-06-11-memory / 04_edit_task / Step 4

---

## Проблема

Endpoint `PATCH /api/tasks/{id}/status` не реализован — возвращает HTTP 404.

Существующие endpoint-ы для смены статуса:
- `POST /api/tasks/{id}/done` — только DONE ✅
- `POST /api/tasks/{id}/confirm` — только PENDING → TODO ✅
- `POST /api/tasks/{id}/reject` — только PENDING → DELETED ✅

Нет универсального endpoint для переходов:
`TODO → IN_PROGRESS → BLOCKED → DONE`

Без него UI не может перевести задачу в IN_PROGRESS или BLOCKED,
и агент через REST не может менять статус произвольно.

---

## Решение

Добавить `@PatchMapping("/{id}/status")` в `TaskController`.  
Принимает `{"status": "IN_PROGRESS"}`, вызывает `taskService.updateStatus(id, status)`.

Допустимые значения статуса для этого endpoint: `TODO`, `IN_PROGRESS`, `BLOCKED`, `DONE`.  
`PENDING`, `DELETED` — только через специализированные endpoints (confirm/reject).

---

## Изменения в коде

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/api/TaskController.java`

```java
@PatchMapping("/{id}/status")
public ResponseEntity<Task> updateStatus(
        @PathVariable Long id,
        @RequestBody UpdateTaskStatusRequest request) {

    // Запретить переход в PENDING или DELETED через этот endpoint
    if ("PENDING".equals(request.status()) || "DELETED".equals(request.status())) {
        return ResponseEntity.badRequest().build();
    }

    Task updated = taskService.updateStatus(id, request.status());
    return ResponseEntity.ok(updated);
}
```

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/dto/UpdateTaskStatusRequest.java`

```java
public record UpdateTaskStatusRequest(String status) {}
```

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/service/TaskService.java`

Метод `updateStatus` уже объявлен в RFC — убедиться что реализован:

```java
public Task updateStatus(Long id, String status) {
    Task task = taskRepository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("Task not found: " + id));
    Task updated = new Task(
        task.id(), task.planId(), task.title(), task.description(),
        status,  // ← новый статус
        task.priority(), task.dueDate(), task.source(), task.emailId(),
        task.sortOrder(), task.createdAt(), Instant.now()
    );
    return taskRepository.save(updated);
}
```

---

## Допустимые переходы статусов

```
PENDING  → confirm() → TODO
PENDING  → reject()  → DELETED
TODO     → PATCH /status → IN_PROGRESS | BLOCKED | DONE
IN_PROGRESS → PATCH /status → TODO | BLOCKED | DONE
BLOCKED  → PATCH /status → TODO | IN_PROGRESS
DONE     → PATCH /status → TODO  (возврат, если ошиблись)
```

---

## Как проверить

```bash
JavaMemoryService/test_e2e/04_edit_task.md → Step 4
```

Ручная проверка:
```bash
# Создать задачу
TASK_ID=$(curl -s -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"Test","date":"'$(date +%Y-%m-%d)'","priority":"NORMAL","source":"MANUAL"}' \
  | jq -r '.id')

# Перевести в IN_PROGRESS
curl -s -X PATCH "http://localhost:8082/api/tasks/$TASK_ID/status" \
  -H "Content-Type: application/json" \
  -d '{"status":"IN_PROGRESS"}'
# Ожидается: HTTP 200, "status":"IN_PROGRESS"
```
