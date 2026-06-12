# CR-MEM-001: Capture Bot — модуль захвата сырых заметок

**Дата:** 2026-06-12
**Статус:** Approved
**Сервис:** MEM (JavaMemoryService)
**Зависимости:** нет — независимый модуль, файловая шина как хранилище

---

## Проблема / Мотивация

В течение дня накапливается поток наблюдений: после встреч, звонков, из чатов.
Если пытаться классифицировать их сразу — это тормозит поток и снижает качество
классификации (нет контекста всего дня).

Нужен механизм быстрого захвата без интерпретации. Классификация — отложенная,
в конце дня, когда агент видит все заметки разом.

**Принцип:** capture now, classify later.

---

## Решение

### 1. Endpoint приёма заметок

**POST /api/capture**

Request body:
```json
{
  "text": "Иван не знает rollback процедуру",
  "source": "agent | manual | bot"
}
```

Response:
```json
{
  "file": "capture-inbox/2026-06-12/14-32-07.md",
  "saved": true
}
```

Файл сохраняется немедленно в:
```
capture-inbox/YYYY-MM-DD/HH-MM-SS.md
```

Формат файла:
```
---
date: YYYY-MM-DD HH:MM:SS
source: agent | manual | bot
---
<текст дословно, без изменений>
```

**Правила:**
- Никакой классификации при сохранении
- Никаких валидаций содержимого — только raw текст
- Если директория для даты не существует — создать автоматически
- Конфликт имён при одной секунде: добавить суффикс `-1`, `-2` и т.д.

---

### 2. Scheduler классификации

**`CaptureScheduler`** — cron из properties (default каждый час, CR-MEM-002)

Алгоритм:
```
1. Найти все .md файлы в capture-inbox/YYYY-MM-DD/ (сегодня, не из processed/)
2. Если список пуст → выход
3. GET /api/context → текущие задачи + открытые риски (CR-MEM-002)
4. Сформировать батч-промпт (все файлы + контекст дня)
5. claude --print "<промпт>"
6. Если JSON невалидный → log.warn, выход (все файлы остаются)
7. Для каждого элемента из JSON:
   a. Выполнить action (createPending / createRisk / createNote)
   b. Если action успешен → mv файл в capture-inbox/processed/YYYY-MM-DD/
   c. Если action упал   → log.warn, файл остаётся
```

**Промпт для агента:**
```
Классифицируй каждую заметку. Верни ТОЛЬКО JSON массив, без пояснений.

Типы классификации:
- TASK — действие которое нужно выполнить
- RISK — операционный риск или проблема
- NOTE — наблюдение, информация к сведению

Заметки:
[{"file": "10-32-00.md", "text": "..."},
 {"file": "14-15-00.md", "text": "..."}]

Формат ответа:
[
  {"file": "10-32-00.md", "type": "TASK", "title": "...", "priority": "HIGH|NORMAL|LOW"},
  {"file": "14-15-00.md", "type": "RISK", "title": "..."},
  {"file": "16-40-00.md", "type": "NOTE",  "title": "..."}
]
```

**Детерминированные действия по типу:**

| Тип | Действие |
|-----|----------|
| `TASK` | `POST /api/tasks/pending` — задача ждёт подтверждения в `/ui/today` |
| `RISK` | `POST /api/risks` — сохранить в PostgreSQL (probability=MEDIUM, impact=MEDIUM, status=OPEN) |
| `NOTE` | `POST /api/notes` — сохранить в таблицу notes |

---

### 3. Новая таблица `notes`

```sql
CREATE TABLE memory.notes (
    id         BIGSERIAL PRIMARY KEY,
    text       TEXT         NOT NULL,
    tags       VARCHAR(500),          -- через запятую, заполняется агентом
    source     VARCHAR(50)  NOT NULL DEFAULT 'agent',  -- agent | manual | capture
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notes_created_at ON memory.notes(created_at DESC);
```

Миграция: `V4__add_notes_and_capture.sql`

---

## Изменения в API

| Метод | Путь | Описание |
|-------|------|----------|
| `POST` | `/api/capture` | Сохранить raw заметку в файловую шину |
| `POST` | `/api/notes` | Сохранить заметку в PostgreSQL (вызывается CaptureScheduler) |
| `GET`  | `/api/notes` | Список заметок (фильтр: `?tags=risk,person&limit=50`) |

---

## Новые компоненты

```
JavaMemoryService/src/main/java/ru/andreyz/memoryservice/
├── capture/
│   ├── CaptureController.java    ← POST /api/capture
│   ├── CaptureService.java       ← запись файла в capture-inbox/
│   └── CaptureScheduler.java     ← @Scheduled, запуск claude --print
├── notes/
│   ├── Note.java                 ← @Table("memory.notes") record
│   ├── NoteRepository.java       ← CrudRepository
│   ├── NoteService.java          ← save, list, filter by tags
│   └── NoteController.java       ← GET/POST /api/notes
└── ui/
    └── NotesViewController.java  ← GET /ui/notes
```

---

## Изменения в файловой шине

```
Leader-Role-Framework/
└── capture-inbox/
    ├── YYYY-MM-DD/
    │   ├── HH-MM-SS.md        ← raw заметки текущего дня
    │   └── HH-MM-SS.md
    └── processed/
        └── YYYY-MM-DD/        ← обработанные файлы после запуска scheduler
            └── HH-MM-SS.md
```

---

## Как тестировать

```bash
# 1. Сохранить заметку
curl -X POST http://localhost:8082/api/capture \
  -H "Content-Type: application/json" \
  -d '{"text": "Иван не знает процедуру rollback", "source": "manual"}'

# 2. Проверить файл в capture-inbox/
ls -la Leader-Role-Framework/capture-inbox/$(date +%Y-%m-%d)/

# 3. Принудительно запустить scheduler (только dev/local)
curl -X POST http://localhost:8082/api/capture/process-now

# 4. Проверить что PENDING задача появилась в UI
open http://localhost:8082/ui/today

# 5. Проверить что NOTE попала в ленту
open http://localhost:8082/ui/notes
```
