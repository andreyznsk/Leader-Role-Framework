# CR-MEM-031: Связи между задачами (task links)

**Дата:** 2026-07-02
**Статус:** Draft
**Сервис:** JavaMemoryService
**Зависимости:** CR-MEM-023 (control panel), CR-MEM-015 (tsvector — автокомплит поиска задач), CR-MEM-024 (MCP proposal-модель)

## Проблема / Мотивация
Задачи нельзя связывать между собой (relates to / blocks / duplicates / parent). Существующее поле `tasks.linked_to_task_id` — одиночное и семантически принадлежит intake-flow (привязка pending-предложения к задаче); для графа связей оно не подходит и **не трогается** этим CR.

## Решение
Отдельная таблица направленных связей. Обратная связь выводится зеркально при чтении (BLOCKS ↔ BLOCKED_BY, PARENT_OF ↔ CHILD_OF), в БД хранится одна запись.

Типы: `RELATES_TO` (симметричный), `BLOCKS`, `DUPLICATES`, `PARENT_OF`.

Правила:
- запрет self-link (`from = to`) → 400
- запрет точного дубля (uniq constraint) → 409
- при удалении задачи связи удаляются каскадно (обе стороны)

**MCP:** прямых write-tools не добавляем. В духе CR-MEM-024 — новый proposal-tool `proposeTaskLink(fromTaskId, toTaskId, linkType, reason)` → Intake Gateway → ручной Apply.

## Изменения в API
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/tasks/{id}/links` | JSON `{toTaskId, linkType}` → 201 |
| GET | `/api/tasks/{id}/links` | все связи задачи, исходящие + зеркальные входящие, с `direction: OUT|IN` и title связанной задачи |
| DELETE | `/api/tasks/{id}/links/{linkId}` | удалить связь |

## Изменения в схеме БД
Миграция `V17__task_links.java`:
```sql
CREATE TABLE task_links (
    id BIGSERIAL PRIMARY KEY,
    from_task_id BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    to_task_id   BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    link_type VARCHAR(16) NOT NULL,   -- RELATES_TO | BLOCKS | DUPLICATES | PARENT_OF
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_task_links UNIQUE (from_task_id, to_task_id, link_type),
    CONSTRAINT chk_no_self_link CHECK (from_task_id <> to_task_id)
);
CREATE INDEX idx_task_links_from ON task_links(from_task_id);
CREATE INDEX idx_task_links_to   ON task_links(to_task_id);
```

## Изменения в UI
- Блок «Linked tasks» в правой control-панели task-edit: список связей (тип + title + статус связанной задачи, клик → переход), кнопка «+ Link» с выбором типа и автокомплитом по задачам (поверх search-провайдера CR-MEM-015).
- В `/ui/today` на карточке — компактный бейдж количества связей (опционально, фаза 2).

## Изменения в MCP
| Tool | Описание |
|------|----------|
| `proposeTaskLink` | proposal в Intake Gateway; при Apply создаётся запись в task_links |
| `getTasks` / `getTaskDescription` | в ответ добавить массив `links` (read-only) |

## Зависимости от других сервисов
Нет.

## Как тестировать
```
1. POST link A→B (BLOCKS) → 201; GET links у A: direction=OUT BLOCKS B; у B: direction=IN (BLOCKED_BY A)
2. Повторный POST того же линка → 409
3. POST A→A → 400
4. DELETE задачи B → связь исчезла у A
5. MCP proposeTaskLink → карточка в /ui/intake → Apply → связь создана
6. UI: добавить связь через автокомплит, перейти по клику на связанную задачу
```
