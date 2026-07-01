# LeaderOS Documentation Workflow

**Дата:** 2026-06-14  
**Статус:** Draft  
**Назначение:** Зафиксировать рабочий процесс между Google Drive, GitHub `docs/` и локальным агентом.

---

## 1. Принцип Source of Truth

Главная проектная документация LeaderOS продолжает жить в Google Drive.

Google Drive остаётся основным источником истины для:

- README / обзор проекта;
- ARCHITECTURE.md;
- RFC;
- ADR;
- roadmap;
- отчётов;
- итоговой документации после применения изменений.

Правило при конфликте:

```text
Google Drive > GitHub docs/ > Chat Context
```

---

## 2. Роль GitHub `docs/`

Папка `docs/` в репозитории используется как staging-зона для будущих изменений и рабочих артефактов.

Туда можно складывать:

- CR;
- RFC draft;
- ADR draft;
- схемы;
- HTML-презентации;
- meeting notes;
- отчёты;
- инструкции для локального агента;
- временные документы для review.

GitHub `docs/` — не финальный Source of Truth, а рабочая зона изменений.

---

## 3. Целевой flow

```text
1. Пользователь формулирует идею / задачу / изменение
        ↓
2. ChatGPT создаёт CR или другой артефакт в GitHub: docs/...
        ↓
3. Пользователь делает pull локально
        ↓
4. Пользователь запускает локального агента / Codex / Claude Code
        ↓
5. Агент читает CR и применяет изменения в проекте и документации
        ↓
6. Пользователь проверяет результат локально
        ↓
7. Пользователь обновляет Google Drive документацию вручную
        ↓
8. Пользователь синхронизирует обновлённые документы с ChatGPT project context
        ↓
9. Google Drive снова становится актуальным Source of Truth
```

---

## 4. Структура папки `docs/`

Рекомендуемая структура:

```text
docs/
├── cr/                 # Change Requests
├── rfc/                # Draft RFC
├── adr/                # Draft ADR
├── meetings/           # Meeting notes and briefs
├── reports/            # Test reports, summaries, weekly reports
├── diagrams/           # Mermaid / PlantUML / architecture diagrams
├── presentations/      # HTML presentations and supporting assets
└── workflow/           # Process descriptions and agent instructions
```

---

## 5. Правила создания CR

CR создаётся в:

```text
docs/cr/CR-{PREFIX}-{NNN}-{short-name}.md
```

Примеры:

```text
docs/cr/CR-MEM-009-usage-statistics-events.md
docs/cr/CR-RAG-003-indexing-status-ui.md
docs/cr/CR-ARCH-003-docs-staging-workflow.md
```

Минимальный шаблон CR:

```markdown
# CR-{PREFIX}-{NNN}: Название изменения

**Дата:** YYYY-MM-DD
**Статус:** Draft | Review | Approved | Implemented
**Сервис:** MEM | RAG | MAIL | COMMON | ARCH | TEST | DOCS
**Зависимости:** ...

## Проблема / Мотивация

## Решение

## Изменения в API

## Изменения в схеме БД

## Зависимости от других сервисов

## Как тестировать
```

---

## 6. Правила для встреч

Если создаётся встреча по CR или RFC:

1. ChatGPT создаёт документ/артефакт в `docs/`.
2. ChatGPT создаёт событие в Google Calendar.
3. В описание события добавляется GitHub-ссылка на артефакт.
4. После локальной обработки и обновления Google Drive ссылка на итоговый Drive-документ может быть добавлена вручную или отдельным шагом.

---

## 7. Роль локального агента

Локальный агент работает от GitHub `docs/`:

```text
1. git pull
2. найти новые docs/cr/*.md
3. прочитать CR
4. применить изменения в коде / документации
5. обновить локальные README / ARCHITECTURE / RFC
6. прогнать тесты
7. подготовить отчёт
```

GitHub `docs/` используется как входной backlog для агента.

---

## 8. Роль пользователя

Пользователь отвечает за финальную синхронизацию:

- проверяет изменения локально;
- принимает или отклоняет результат агента;
- загружает обновлённую финальную документацию в Google Drive;
- синхронизирует Google Drive документы с ChatGPT project context.

---

## 9. Итоговое правило

```text
ChatGPT пишет будущие изменения в GitHub docs/.
Локальный агент применяет изменения.
Пользователь публикует финальную документацию в Google Drive.
Google Drive остаётся главным источником истины.
```
