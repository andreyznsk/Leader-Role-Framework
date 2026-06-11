# CR-MEM-003: UI — менеджер задач, форма редактирования, файловое описание

**Дата:** 2026-06-10  
**Статус:** Approved  
**Сервис:** MEM — JavaMemoryService  
**Зависимости:** ARCHITECTURE.md (файловая шина `workspace/tasks/`)

---

## Проблема / Мотивация

В RFC-memory-service раздел 9 (Thymeleaf UI) описан схематично — без деталей
интерактивности, порядка сортировки задач и способа хранения описания.
Необходимо зафиксировать решения, принятые в ходе UI-сессии 2026-06-10.

---

## Решение

### 1. Стек UI

- **Bootstrap 5** — подключается через CDN, кастомный CSS не используется
- **Thymeleaf** — шаблоны с фрагментом `fragments/layout.html` (nav + head)
- Формы через POST (не AJAX) — проще и надёжнее для MVP

```html
<!-- fragments/layout.html — head секция -->
<link rel="stylesheet"
      href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css">
<script src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/js/bootstrap.bundle.min.js"
        defer></script>
```

---

### 2. Страница `/ui/today` — менеджер задач

#### Секции страницы (порядок сверху вниз)

1. **Сводка** — 4 карточки: задач сегодня / ожидают подтверждения / открытые инциденты / выполнено
2. **Ожидают подтверждения** — показывается только если есть PENDING задачи
3. **План на сегодня** — список задач с управлением
4. **Завтра** — список задач на следующий день (только просмотр + действия)

#### Управление задачами в списке

Каждая строка задачи содержит:

| Элемент | Действие | HTTP |
|---------|----------|------|
| Чекбокс | `DONE` / снять | `POST /api/tasks/{id}/done` |
| Стрелка ↑ | переместить вверх | `POST /api/tasks/{id}/reorder` `{"direction":"up"}` |
| Стрелка ↓ | переместить вниз | `POST /api/tasks/{id}/reorder` `{"direction":"down"}` |
| Иконка флага | циклически менять приоритет | `PUT /api/tasks/{id}` `{"priority":"..."}` |
| Иконка карандаша | открыть форму редактирования | `GET /ui/tasks/{id}/edit` |
| Иконка корзины | удалить (без подтверждения) | `POST /api/tasks/{id}/delete` |
| Drag handle `⠿` | drag-and-drop сортировка | `POST /api/tasks/{id}/reorder` `{"position":N}` |

#### Добавление задачи

Кнопка "добавить" раскрывает inline-строку под списком:
- поле `title` (text, required)
- select `priority` (NORMAL по умолчанию)
- кнопка "добавить" → `POST /api/tasks`

#### Поле sort_order

Добавить колонку в схему БД:

```sql
ALTER TABLE tasks ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
CREATE INDEX idx_tasks_sort_order ON tasks(plan_id, sort_order);
```

При создании задачи `sort_order = MAX(sort_order) + 1` в рамках `plan_id`.

---

### 3. Форма редактирования задачи `/ui/tasks/{id}/edit`

#### Поля формы

| Поле | Тип | Источник данных |
|------|-----|----------------|
| `title` | text input | колонка `tasks.title` (PostgreSQL) |
| `description` | Markdown textarea | файл `workspace/tasks/TASK-{id}.md` (файловая шина) |
| `priority` | select | колонка `tasks.priority` |
| `due_date` | date input | колонка `tasks.due_date` |
| `status` | radio-пилюли | колонка `tasks.status` |
| `source` | readonly badge | колонка `tasks.source` + `tasks.email_id` |

#### Markdown-редактор описания

Две вкладки над textarea:
- `markdown` — raw редактор, `font-family: monospace`
- `preview` — простой HTML-рендер через JS (без библиотек)

Под textarea — бейдж с путём к файлу:
```
📄 workspace/tasks/TASK-003.md
```

#### Логика сохранения

```
POST /ui/tasks/{id}/edit
        ↓
TaskEditController
  ├── taskRepository.save(task с новыми метаданными)   ← PostgreSQL
  └── Files.writeString(                               ← файловая шина
        workDir.resolve("workspace/tasks/TASK-" + String.format("%03d", id) + ".md"),
        req.description(),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
```

#### Логика загрузки описания

```
GET /ui/tasks/{id}/edit
        ↓
TaskEditController
  ├── task = taskRepository.findById(id)
  ├── filePath = workDir.resolve("workspace/tasks/TASK-{id}.md")
  └── description = Files.exists(filePath)
                    ? Files.readString(filePath)
                    : ""   ← файл может отсутствовать для старых задач
```

#### Удаление задачи

Двойное подтверждение:
1. Первый клик: кнопка меняет текст на "точно удалить?" (3 сек таймаут)
2. Второй клик: `POST /api/tasks/{id}/delete` + `Files.deleteIfExists(taskFile)`

---

### 4. Endpoint для описания (REST)

Добавить к существующему API:

```
GET  /api/tasks/{id}/description   → 200 text/plain (содержимое файла) | 204 (файл отсутствует)
PUT  /api/tasks/{id}/description   body: text/plain → записать в файл
```

Агент читает описание через MCP tool `getTaskDescription(id)` — реализуется поверх этого endpoint.

---

### 5. Структура файлов описаний

```
Leader-Role-Framework/
└── workspace/
    └── tasks/
        ├── TASK-001.md    ← описание задачи #1
        ├── TASK-002.md
        └── TASK-003.md
```

**Формат файла** — свободный Markdown, агент пишет в произвольном формате.
Рекомендуемая структура (не обязательная):

```markdown
## Контекст
...

## Что нужно сделать
- пункт 1
- пункт 2

## Дедлайн
...
```

---

## Изменения в API

| Метод | Путь | Новый | Описание |
|-------|------|-------|----------|
| `POST` | `/api/tasks/{id}/reorder` | ✅ | Изменить порядок задачи |
| `GET` | `/api/tasks/{id}/description` | ✅ | Читать файл описания |
| `PUT` | `/api/tasks/{id}/description` | ✅ | Записать файл описания |
| `GET` | `/ui/tasks/{id}/edit` | ✅ | Страница редактирования |
| `POST` | `/ui/tasks/{id}/edit` | ✅ | Сохранить изменения |

---

## Изменения в схеме БД

```sql
-- Добавить поле сортировки
ALTER TABLE tasks ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
CREATE INDEX idx_tasks_sort_order ON tasks(plan_id, sort_order);
```

Flyway: `V2__add_task_sort_order.sql`

---

## Изменения в структуре проекта

```
JavaMemoryService/src/main/
├── java/ru/andreyz/memoryservice/
│   ├── api/
│   │   └── TaskDescriptionController.java   ← NEW: GET/PUT /api/tasks/{id}/description
│   ├── ui/
│   │   └── TaskEditController.java          ← NEW: GET/POST /ui/tasks/{id}/edit
│   └── service/
│       └── TaskFileService.java             ← NEW: read/write workspace/tasks/TASK-{id}.md
└── resources/templates/
    ├── fragments/layout.html                ← Bootstrap 5 CDN head + nav
    ├── today.html                           ← обновить: sort_order, drag handle, inline add
    └── task-edit.html                       ← NEW: форма редактирования с MD-редактором
```

---

## Зависимости от других сервисов

- **Файловая шина** — `workspace/tasks/` должна существовать при старте сервиса.
  `TaskFileService` создаёт директорию при инициализации (`@PostConstruct`).
- **Агент (Claude Code)** — читает `workspace/tasks/TASK-{id}.md` через filesystem MCP.
  Формат файла свободный, агент не ограничен структурой.

---

## Как тестировать

```bash
# 1. Создать задачу
curl -X POST http://localhost:8082/api/tasks \
  -H "Content-Type: application/json" \
  -d '{"title":"Тест","priority":"HIGH","date":"2026-06-10","source":"MANUAL"}'

# 2. Записать описание
curl -X PUT http://localhost:8082/api/tasks/1/description \
  -H "Content-Type: text/plain" \
  -d '## Контекст\nТестовое описание'

# 3. Проверить файл
cat Leader-Role-Framework/workspace/tasks/TASK-001.md

# 4. UI редактирование
open http://localhost:8082/ui/tasks/1/edit

# 5. Сортировка
curl -X POST http://localhost:8082/api/tasks/1/reorder \
  -H "Content-Type: application/json" \
  -d '{"direction":"up"}'
```
