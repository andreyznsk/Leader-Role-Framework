# CR-MEM-BUGFIX-001: HTTP 405 — Thymeleaf форма не поддерживает PUT

**Дата:** 2026-06-11  
**Статус:** Implemented  
**Сервис:** MEM — JavaMemoryService  
**Тип:** bugfix  
**Зависимости:** CR-MEM-003 (task-edit.html)

---

## Проблема

```
DefaultHandlerExceptionResolver: Resolved [HttpRequestMethodNotSupportedException:
Request method 'POST' is not supported]
GET http://localhost:8082/api/tasks/1 → 405 Method Not Allowed
```

HTML-формы поддерживают только `GET` и `POST`.  
Thymeleaf шлёт `POST`, контроллер ожидает `PUT` → 405.  
MCP-агент шлёт настоящий `PUT` напрямую — у него ошибки нет.

---

## Причина

В `task-edit.html` форма объявлена без `_method`:

```html
<!-- было — неправильно -->
<form th:action="@{/api/tasks/{id}(id=${task.id})}" method="post">
```

Spring не знает что нужно смаршрутить на `@PutMapping`.

---

## Решение

### 1. `application.properties` — включить фильтр (одна строка)

```properties
spring.mvc.hiddenmethod.filter.enabled=true
```

`HiddenHttpMethodFilter` перехватывает POST-запросы со скрытым параметром `_method`
и подменяет HTTP-метод перед маршрутизацией. Работает глобально, настраивается один раз.

### 2. `task-edit.html` — добавить скрытое поле в форму

```html
<!-- стало — правильно -->
<form th:action="@{/api/tasks/{id}(id=${task.id})}" method="post">
    <input type="hidden" name="_method" value="PUT">
    <!-- остальные поля без изменений -->
</form>
```

### 3. Контроллер — не трогать

```java
// TaskController — остаётся как есть
@PutMapping("/api/tasks/{id}")
public ResponseEntity<Task> updateTask(@PathVariable Long id,
                                       @RequestBody EditTaskRequest req) { ... }
```

---

## Схема потоков после фикса

```
Thymeleaf форма  →  POST /api/tasks/1 (_method=PUT)
                         ↓ HiddenHttpMethodFilter
                    PUT /api/tasks/1  →  @PutMapping  ✅

MCP агент        →  PUT /api/tasks/1  →  @PutMapping  ✅
```

Один endpoint, два клиента, один контроллер.

---

## Правило для остальных шаблонов

Скрытое поле добавлять **только там где есть форма с PUT/DELETE**:

| Шаблон | Метод | Нужен `_method` |
|--------|-------|----------------|
| `task-edit.html` | PUT | ✅ `value="PUT"` |
| `incidents.html` | PUT / DELETE | ✅ по месту |
| `risks.html` | PUT | ✅ по месту |
| `people.html` | PUT | ✅ по месту |
| `today.html` | POST only | ❌ не нужен |
| `fragments/layout.html` | — | ❌ не трогать |

---

## Как проверить

```bash
# Форма сохраняет без 405
open http://localhost:8082/ui/tasks/1/edit
# Заполнить поля → нажать "сохранить" → должен быть redirect без ошибки

# MCP по-прежнему работает
curl -X PUT http://localhost:8082/api/tasks/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"Тест","priority":"HIGH"}'
# → 200 OK
```

---

## Коммит

```
MEM_bugfix_001 HTTP 405 fix — HiddenHttpMethodFilter + _method в task-edit.html
```
