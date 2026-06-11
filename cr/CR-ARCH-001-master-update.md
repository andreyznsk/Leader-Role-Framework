# CR-ARCH-001: Обновление мастер-спеки

**Дата:** 2026-06-09
**Статус:** Implemented
**Сервис:** ARCHITECTURE.md
**Зависимости:** нет

---

## 1. Переименование проекта

```
Leader-Role-Framework → LeaderOS
```

Обновить заголовок и все упоминания в документе.

---

## 2. Обновить файловую шину

```
Leader-Role-Framework/
├── inbox/              ← JavaMailAgent пишет входящие письма (JSON)
├── processed/          ← письма после обработки
├── drafts/             ← черновики ответов от агента
├── plans/
│   └── today.md        ← план дня
├── capture-inbox/      ← Capture Bot складывает сырые заметки (NEW)
│   └── YYYY-MM-DD/
│       └── HH-MM-SS.md
├── workspace/
│   └── tasks/          ← файлы задач по id (NEW)
│       ├── TASK-001.md
│       └── TASK-002.md
└── cr/                 ← CR для ARCHITECTURE.md и CLAUDE.md (NEW)
    ├── CR-ARCH-001-master-update.md
    └── CR-CLAUDE-001-handoff.md
```

---

## 3. Обновить связи между сервисами

```
JavaMailAgent  ──POST /api/tasks/pending──→  JavaMemoryService
JavaMailAgent  ──запускает──→  claude --print
JavaMemoryService ──GET /api/calendar/today──→  JavaMailAgent  (NEW — идея 8)
claude --print ──читает──→  JavaRagService (через MCP или HTTP /api/search)
```

---

## 4. Обновить CR workflow — префиксы и структура

### Префиксы

| Префикс | Сервис / файл |
|---------|--------------|
| `MEM` | JavaMemoryService |
| `RAG` | JavaRagService |
| `MAIL` | JavaMailAgent |
| `CLAUDE` | CLAUDE.md |
| `ARCH` | ARCHITECTURE.md |

### Структура папок

```
Leader-Role-Framework/
├── cr/                               ← ARCH и CLAUDE
│   ├── CR-ARCH-001-master-update.md
│   ├── CR-CLAUDE-001-handoff.md
│   └── ...
├── JavaMemoryService/cr/
│   ├── CR-MEM-001-capture-bot.md
│   ├── CR-MEM-002-task-file-storage.md
│   └── ...
├── JavaRagService/cr/
│   └── CR-RAG-001-embeddings.md
└── JavaMailAgent/cr/
    └── CR-MAIL-001-calendar-endpoint.md
```

### Шаблон CR (обновлённый)

```markdown
# CR-{PREFIX}-{NNN}: Название изменения

**Дата:** YYYY-MM-DD
**Статус:** Draft | Review | Approved | Implemented
**Сервис:** MEM | RAG | MAIL | CLAUDE | ARCH
**Зависимости:** ...

## Проблема / Мотивация
## Решение
## Изменения в API
## Изменения в схеме БД
## Зависимости от других сервисов
## Как тестировать
```

---

## 5. Правила коммитов

**Формат:** `{PREFIX}_{тип}_{номер} {краткое описание}`

| Тип | Когда |
|-----|-------|
| `cr` | изменение по Change Request |
| `bugfix` | исправление бага |
| `manual` | ручное изменение без CR |
| `eod` | автоматический коммит конца дня |

**Примеры:**
```
MEM_cr_001 добавлен capture bot модуль
MEM_cr_002 task file storage реализован
RAG_cr_001 подключён multilingual-e5-large
MAIL_bugfix_042 исправлен парсинг EWS дат
ARCH_manual обновлена схема связей сервисов
INFRA_manual добавлен opensearch в docker-compose
MEM_eod_2026-06-09 резюме дня
```

---

## 6. Обновить раздел Future

Убрать устаревшее, добавить актуальное:

**Убрать:**
- Chat-бот в JavaMailAgent
- Obsidian — экспорт заметок
- macOS уведомления — osascript (уже реализовано)

**Добавить:**
- **Capture Bot** (CR-MEM-001) — модуль приёма заметок в java-memory-service
- **Task File Storage** (CR-MEM-002) — файлы задач workspace/tasks/TASK-{id}.md
- **Calendar Endpoint** (CR-MAIL-001) — GET /api/calendar/today из EWS
- **Weekly Routine Manager** (идея 8) — UI routines + briefing по расписанию
- **End of Day Summary** (идея 9) — git diff + резюме + EOD коммит
- **LeaderOS Daily Cycle** — суточный цикл фреймворка (отдельный RFC)
- **Grafana** — capacity из Jira + PostgreSQL (идея 3)
