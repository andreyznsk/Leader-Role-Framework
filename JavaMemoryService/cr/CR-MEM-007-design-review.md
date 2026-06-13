# CR-MEM-007: Design Review — LeaderOS UI

**Дата:** 2026-06-13
**Статус:** Draft
**Сервис:** MEM — JavaMemoryService
**Зависимости:** leaderOS-pitch-v5.html как источник стиля

---

## Проблема / Мотивация

1. UI memory service не соответствует стилю презентации LeaderOS
2. Деление задач на "сегодня/завтра" неудобно — важнее статус задачи
3. Переход на презентацию открывает её без навигации и без возможности вернуться

---

## Изменение 1: Тема LeaderOS для всего UI

### CSS переменные (единый style.css)

```css
:root {
  --bg:      #0D1117;
  --bg2:     #161B22;
  --bg3:     #21262D;
  --border:  #30363D;
  --accent:  #00D4AA;
  --accent2: #4A9EFF;
  --accent3: #F78166;
  --text:    #E6EDF3;
  --text2:   #8B949E;
  --text3:   #6E7681;
  --mono:    'JetBrains Mono', monospace;
  --sans:    'Inter', sans-serif;
}
body {
  background: var(--bg);
  color: var(--text);
  font-family: var(--sans);
}
```

### fragments/layout.html — новый навбар

```html
<nav class="navbar">
  <div class="nav-brand">
    <div class="nav-dot"></div>
    <span class="nav-title">Leader<span class="nav-accent">OS</span></span>
  </div>
  <div class="nav-links">
    <a href="/ui/today">📅 План дня</a>
    <a href="/ui/notes">📝 Заметки</a>
    <a href="/ui/incidents">🚨 Инциденты</a>
    <a href="/ui/risks">⚠️ Риски</a>
    <a href="/ui/people">👥 Команда</a>
    <a href="/ui/presentation" target="_blank">🚀 Презентация</a>
  </div>
</nav>
```

Стиль навбара:
- Фон `#161B22`, бордер-bottom `1px solid #30363D`
- Логотип: анимированная точка-пульс + JetBrains Mono
- Активная ссылка: акцент `#00D4AA`
- Презентация открывается в `target="_blank"`

### Применить тему ко всем страницам:
- `today.html`
- `notes.html`
- `incidents.html`
- `risks.html`
- `people.html`
- `task-edit.html`
- `presentation.html` (новая)

---

## Изменение 2: Переработка /ui/today

### Убрать деление сегодня/завтра

**Было:**
```
Секция: "Сегодня" (задачи на today)
Секция: "Завтра"  (задачи на tomorrow)
```

**Стало:**
```
Секция 1: "Ожидают подтверждения" (status = PENDING)
          — показывать только если есть PENDING задачи
          — карточка: заголовок + sender + priority
          — кнопки: [Принять] [Изменить] [Отклонить]

Секция 2: "Текущие задачи" (status IN: TODO, IN_PROGRESS, DONE, BLOCKED)
          — все задачи без привязки к дате
          — сортировка: IN_PROGRESS → TODO → BLOCKED → DONE
          — управление: чекбокс, стрелки, приоритет, редактировать, удалить
```

### Изменения в TodayViewController.java

```java
// Было
model.addAttribute("todayTasks", taskService.findByDate(today));
model.addAttribute("tomorrowTasks", taskService.findByDate(tomorrow));

// Стало
model.addAttribute("pendingTasks", taskService.findByStatus("PENDING"));
model.addAttribute("currentTasks", taskService.findCurrentTasks());
// findCurrentTasks() = все задачи со статусом != PENDING, != DELETED
// сортировка: IN_PROGRESS → TODO → BLOCKED → DONE
```

### Изменения в TaskRepository.java

```java
@Query("SELECT * FROM tasks WHERE status NOT IN ('PENDING','DELETED') " +
       "ORDER BY CASE status " +
       "WHEN 'IN_PROGRESS' THEN 1 " +
       "WHEN 'TODO' THEN 2 " +
       "WHEN 'BLOCKED' THEN 3 " +
       "WHEN 'DONE' THEN 4 END, sort_order")
List<Task> findCurrentTasks();
```

---

## Изменение 3: Презентация как вкладка с навигацией

### Новый роут /ui/presentation

```java
@GetMapping("/ui/presentation")
public String presentation() {
    return "presentation";
}
```

### templates/presentation.html

```html
<div th:replace="fragments/layout :: layout(~{::content})">
  <div th:fragment="content">
    <div class="presentation-container">
      <iframe src="/presentation.html" frameborder="0" allowfullscreen></iframe>
    </div>
  </div>
</div>
```

CSS:
```css
.presentation-container {
  position: fixed;
  top: 56px;
  left: 0; right: 0; bottom: 0;
  background: #0D1117;
}
.presentation-container iframe {
  width: 100%;
  height: 100%;
  border: none;
}
```

### Кнопка в навбаре

```html
<a href="/ui/presentation" target="_blank">🚀 Презентация</a>
```

Логика: кликнуть → новая вкладка `/ui/presentation` →
навбар LeaderOS + iframe с презентацией. Закрыть вкладку = вернуться.

---

## Файлы для изменения

| Файл | Изменение |
|------|-----------|
| `static/style.css` | Полная перезапись под LeaderOS тему |
| `templates/fragments/layout.html` | Новый навбар в стиле LeaderOS |
| `templates/today.html` | PENDING + текущие задачи |
| `templates/presentation.html` | Новый файл — iframe + навбар |
| `ui/TodayViewController.java` | pendingTasks + currentTasks |
| `repository/TaskRepository.java` | findCurrentTasks() |
| `web/PresentationController.java` | GET /ui/presentation |
| `static/presentation.html` | Скопировать leaderOS-pitch-v5.html |

---

## Как тестировать

1. `mvn package -q -DskipTests`
2. `SPRING_PROFILES_ACTIVE=local java -jar target/memory-service.jar`
3. Открыть `http://localhost:8082/ui/today`
   - ✅ тёмная тема LeaderOS
   - ✅ навбар с логотипом и ссылками
   - ✅ секция PENDING (если есть задачи)
   - ✅ секция "Текущие задачи" без деления по датам
4. Кликнуть "🚀 Презентация"
   - ✅ открывается новая вкладка
   - ✅ навбар LeaderOS сверху
   - ✅ презентация на весь оставшийся экран
   - ✅ стрелки/свайп работают

---

## Объём изменений

Средний: 8 файлов, ~2-3 часа работы Claude Code.
