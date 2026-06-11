# CR-MEM-BUGFIX-002: аудит Thymeleaf шаблонов — найденные проблемы

**Дата:** 2026-06-11  
**Статус:** Approved  
**Сервис:** MEM — JavaMemoryService  
**Тип:** bugfix  
**Зависимости:** CR-MEM-BUGFIX-001 (HiddenHttpMethodFilter)

---

## Проблемы по шаблонам

---

### 1. `task-edit.html` — форма сохраняет через POST, контроллер ждёт PUT

**Симптом:** 405 Method Not Allowed при сохранении задачи.

**Причина:** форма не содержит `_method=PUT`.

**Исправление:**
```html
<!-- было -->
<form method="post" th:action="@{/ui/tasks/{id}/edit(id=${task.id})}">

<!-- стало -->
<form method="post" th:action="@{/ui/tasks/{id}/edit(id=${task.id})}">
    <input type="hidden" name="_method" value="PUT">
```

---

### 2. `risks.html` — форма редактирования риска без `_method`

**Симптом:** 405 при сохранении риска из модалки Edit.

**Причина:** `PUT /ui/risks/{id}/edit` — нет `_method=PUT` в форме.

**Исправление:** в каждой модалке редактирования риска:
```html
<form method="post" th:action="@{/ui/risks/{id}/edit(id=${risk.id})}">
    <input type="hidden" name="_method" value="PUT">  <!-- добавить -->
    ...
</form>
```

---

### 3. `people.html` — форма редактирования человека без `_method`

**Симптом:** 405 при сохранении карточки человека.

**Причина:** `PUT /ui/people/{id}/edit` — нет `_method=PUT`.

**Исправление:** в каждой модалке редактирования:
```html
<form method="post" th:action="@{/ui/people/{id}/edit(id=${person.id})}">
    <input type="hidden" name="_method" value="PUT">  <!-- добавить -->
    ...
</form>
```

---

### 4. `people.html` — некорректный доступ к заметкам через SpEL

**Симптом:** ошибка при рендере страницы или пустые заметки.

**Причина:** динамический ключ в SpEL не работает так:
```html
<!-- НЕПРАВИЛЬНО — Thymeleaf не поддерживает такой динамический доступ к Map -->
<div th:with="notes=${__${'notes_' + person.id}__}">
```

**Правильный подход:** передавать из контроллера `Map<Long, List<PeopleNote>> notesByPerson`:
```java
// PeopleViewController
model.addAttribute("notesByPerson", peopleService.getNotesByPerson());
```

```html
<!-- ПРАВИЛЬНО -->
<div th:each="note : ${notesByPerson[person.id]}">
    <span th:text="${note.note}"></span>
</div>
```

---

### 5. `today.html` — `togglePriority` объявлена внутри IIFE, недоступна глобально

**Симптом:** клик на флаг приоритета — `ReferenceError: togglePriority is not defined`.

**Причина:** функция объявлена внутри `(function() { ... })()`, но `onclick="togglePriority(this)"` ищет её в глобальном скоупе. Строчка `window.togglePriority = togglePriority` есть, но она внутри того же IIFE — это ок, но нужно проверить порядок.

**Исправление:** вынести `togglePriority` из IIFE или убедиться что присвоение `window.togglePriority` выполняется до первого клика:
```javascript
// Вынести ЗА пределы IIFE
function togglePriority(btn) {
    fetch('/api/tasks/' + btn.dataset.id, {
        method: 'PUT',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({priority: btn.dataset.nextPriority})
    }).then(() => location.reload());
}

// IIFE только для drag-and-drop
(function () {
    const list = document.getElementById('today-list');
    if (!list) return;
    // ... drag-drop логика
})();
```

---

### 6. `today.html` — drag-and-drop: `draggable` не сбрасывается после drop

**Симптом:** после drop строка остаётся `draggable=true`, случайные перетаскивания при следующем клике.

**Исправление:** добавить `dragend` listener:
```javascript
list.addEventListener('dragend', e => {
    const row = e.target.closest('.task-row');
    if (row) row.setAttribute('draggable', 'false');
});
```

---

### 7. `incidents.html` — Bootstrap JS подключён в конце body, но уже подключён в `layout.html`

**Симптом:** Bootstrap JS загружается дважды — из `layout.html` (через фрагмент) и явно в конце каждого шаблона.

**Проверить:** если `fragments/layout.html` уже содержит:
```html
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
```
то строку в конце каждого шаблона (`incidents.html`, `risks.html`, `people.html`, `task-edit.html`, `today.html`) нужно **удалить**.

Двойная загрузка не ломает, но тратит трафик и может вызвать предупреждения в консоли.

---

## Итоговый чеклист исправлений

| Файл | Проблема | Действие |
|------|----------|----------|
| `task-edit.html` | нет `_method=PUT` | добавить скрытое поле |
| `risks.html` | нет `_method=PUT` в edit-модалках | добавить скрытое поле |
| `people.html` | нет `_method=PUT` в edit-модалках | добавить скрытое поле |
| `people.html` | SpEL `__${'notes_' + id}__` не работает | передать `notesByPerson: Map<Long, List>` из контроллера |
| `today.html` | `togglePriority` недоступна глобально | вынести из IIFE |
| `today.html` | `draggable` не сбрасывается после drop | добавить `dragend` listener |
| все шаблоны | Bootstrap JS дублируется | убрать из шаблонов, оставить только в `layout.html` |

---

## Коммиты

```
MEM_bugfix_002 _method=PUT добавлен в risks.html и people.html
MEM_bugfix_003 people.html — notesByPerson Map из контроллера вместо SpEL динамики
MEM_bugfix_004 today.html — togglePriority вынесена из IIFE, добавлен dragend listener
MEM_bugfix_005 убрано дублирование Bootstrap JS из шаблонов
```
