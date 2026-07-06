# CR-MEM-031: Связи между задачами (task links)

**Дата:** 2026-07-02
**Статус:** Implemented
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
Миграция `V22__task_links.java` (по паттерну V20, Postgres + H2 fallback). Черновик CR называл
её V17, на момент реализации следующий свободный номер оказался V21 (V17-V20 заняты
mail-linking, intake-gateway, task-attachments — см. CR-MEM-030 с аналогичным расхождением в
нумерации), а при E2E-прогоне 2026-07-03 обнаружился конфликт: в общей dev-БД уже была строка
`flyway_schema_history` для V21 от параллельной сессии (см. project-memory
`flyway-v20-collision-blocked-cr-mem-031`). Разрешено переносом номера — миграция задачи `task
status delegation labels` стала `V21__task_status_delegation_labels.sql`, а `task_links` —
`V22__task_links.java`.
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
| `getTaskLinks(id)` | новый read-only tool — исходящие + зеркальные входящие связи задачи |

**Реализовано без изменений в `getTasks`/`getTaskDescription`:** `getTasks` в MCP отдаёт
domain-record `Task` напрямую (без wrapper-DTO), а `getTaskDescription` — плоскую markdown-строку;
добавлять туда поле `links` означало бы либо ломать их текущий контракт, либо вводить
wrapper-типы без явной необходимости. Вместо этого сделан отдельный read-only tool
`getTaskLinks(id)`, покрывающий тот же сценарий («агент видит связи задачи») тем же способом,
что и REST `GET /api/tasks/{id}/links`.

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

## Implementation Notes (2026-07-03)

**Реализовано:**
- Миграция `V22__task_links.java` (изначально `V21`, переномерована из-за коллизии в общей dev-БД,
  см. раздел «Изменения в схеме БД» выше), домен `TaskLink`, `TaskLinkRepository`, `TaskLinkService`,
  `TaskLinkController` (`POST|GET /api/tasks/{id}/links`, `DELETE /api/tasks/{id}/links/{linkId}`).
- Зеркальные типы при чтении: `BLOCKS→BLOCKED_BY`, `PARENT_OF→CHILD_OF`, `RELATES_TO→RELATES_TO`
  (симметричный, как в тексте CR). Для `DUPLICATES` реверс-имя в CR не было задано явно — по
  аналогии с `BLOCKS/BLOCKED_BY` выбрано `DUPLICATES→DUPLICATED_BY`.
- MCP: `proposeTaskLink` (в `TaskTools`, использует `AgentIntakeProposalService.createTaskLinkProposal`,
  route `TASK_LINK`) и read-only `getTaskLinks(id)` (см. отклонение по `getTasks`/`getTaskDescription`
  выше). `IntakeTargetApplier` получил кейс `TASK_LINK` → `TaskLinkService.create(...)`.
  `IntakeViewController.routeOptions` дополнен `TASK_LINK`.
- UI: секция «Linked tasks» в control-панели `task-edit.html` — список связей с типом,
  title и статусом связанной задачи (клик → переход), автокомплит по `/api/search` (layers=TASK,
  режим QUICK, как в существующем task-search-picker на `/ui/today`), select типа связи, кнопка
  «+ Link», удаление по клику. (Порядковый номер секции в панели впоследствии сместился из-за
  переноса блока Timeline в конец колонки — вне рамок этого CR.)
- Unit-тесты: `TaskLinkControllerTest` (4 кейса: create+mirror+delete, duplicate→409, self-link→400,
  unknown type→400). Полный набор проекта: **138/138 PASS**.
- E2E: `test_e2e/25_task_links.md` (curl-сценарий, 7 шагов + MCP/intake proposal-flow) и
  `test_e2e/tests/task-links.spec.js` (Playwright UI). Прогнан 2026-07-06 после устранения
  Flyway-коллизии (см. выше) — **7/7 PASS**: create+mirror (BLOCKS↔BLOCKED_BY), duplicate→409,
  self-link→400, `proposeTaskLink` → intake → apply → связь создана, delete связи → `204`.

**Реализовано частично (как в CR-MEM-030):** `ON DELETE CASCADE` есть на уровне схемы, но
приложение не делает hard-delete задач (только soft `archive`) — поэтому пункт теста
«DELETE задачи → связь исчезла у другой стороны» не проверяется как отдельный шаг в E2E.