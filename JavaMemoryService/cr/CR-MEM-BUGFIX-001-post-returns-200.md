# CR-MEM-BUGFIX-001: POST endpoints возвращают HTTP 200 вместо 201

**Дата:** 2026-06-11  
**Статус:** Approved  
**Тип:** bugfix  
**Сервис:** JavaMemoryService  
**Источник:** TEST-REPORT-2026-06-11-memory / сценарии 02, 03, 04, 05

---

## Проблема

Все POST-эндпоинты создания ресурсов возвращают `HTTP 200` вместо `HTTP 201 Created`.
Нарушение REST-конвенции. Ломает E2E сценарии и любых внешних клиентов
которые проверяют код ответа.

**Затронутые endpoints:**
- `POST /api/tasks` → возвращает 200, ожидается 201
- `POST /api/tasks/pending` → возвращает 200, ожидается 201

---

## Решение

В `TaskController.java` заменить `ResponseEntity.ok(...)` на
`ResponseEntity.status(HttpStatus.CREATED).body(...)` в методах создания.

---

## Изменения в коде

**Файл:** `JavaMemoryService/src/main/java/ru/andreyz/memoryservice/api/TaskController.java`

```java
// БЫЛО:
@PostMapping
public ResponseEntity<Task> createTask(@RequestBody CreateTaskRequest request) {
    Task task = taskService.createConfirmed(...);
    return ResponseEntity.ok(task);
}

@PostMapping("/pending")
public ResponseEntity<Task> createPendingTask(@RequestBody CreatePendingTaskRequest request) {
    Task task = taskService.createPending(...);
    return ResponseEntity.ok(task);
}

// СТАЛО:
@PostMapping
public ResponseEntity<Task> createTask(@RequestBody CreateTaskRequest request) {
    Task task = taskService.createConfirmed(...);
    return ResponseEntity.status(HttpStatus.CREATED).body(task);
}

@PostMapping("/pending")
public ResponseEntity<Task> createPendingTask(@RequestBody CreatePendingTaskRequest request) {
    Task task = taskService.createPending(...);
    return ResponseEntity.status(HttpStatus.CREATED).body(task);
}
```

---

## Как проверить

Прогнать сценарии:
```bash
JavaMemoryService/test_e2e/02_create_task.md       → Step 1: ожидается HTTP 201
JavaMemoryService/test_e2e/03_read_daily_plan.md   → Step 1, Step 2: HTTP 201
JavaMemoryService/test_e2e/04_edit_task.md         → Step 1: HTTP 201
JavaMemoryService/test_e2e/05_pending_task_flow.md → Step 1, Step 8: HTTP 201
```
