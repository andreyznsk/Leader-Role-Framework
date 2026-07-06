# CR-MEM-030: Вложения и внешние ссылки для задач

**Дата:** 2026-07-02
**Статус:** Implemented (2026-07-03)
**Сервис:** JavaMemoryService
**Зависимости:** CR-MEM-029 (редактор — paste-to-upload, ещё не реализован), CR-MEM-023 (control panel — блок Attachments)

## Проблема / Мотивация
К задаче нельзя приложить файл (скриншот, документ) или ссылку на внешний документ (Google Drive, Confluence). Весь контекст приходится вклеивать текстом в описание.

## Решение
Единая модель вложений с двумя видами:
- `kind = FILE` — файл хранится на **файловой системе**: `workspace/attachments/{taskId}/{uuid}_{sanitizedFilename}`. В БД — только метаданные и относительный `storage_ref`.
- `kind = LINK` — внешний URL (Drive/Confluence/etc.), байты не храним.

Конфигурация (namespace `app.*`, по конвенции остальных custom-properties сервиса — не `memory.*`, как в исходном черновике):
```yaml
app:
  attachments:
    dir: workspace/attachments      # переопределяемо
    max-file-size: 20MB
    allowed-mime-prefixes: image/,application/pdf,text/

spring:
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 21MB
```

Удаление задачи → каскадное удаление записей (FK) + cleanup файлов в сервисе.
**Реализовано частично:** FK `ON DELETE CASCADE` есть на уровне схемы, но в приложении нет hard-delete для tasks (только soft `archive`, см. `TaskService.archive`) — поэтому автоматический cleanup файлов при архивации задачи НЕ реализован (архивная задача не теряет данные, cascade сработает только если задача когда-нибудь будет удалена напрямую в БД).

Скачивание — стриминг с корректным `Content-Type` и `Content-Disposition`; для `image/*` — inline (нужно для встраивания в редактор CR-MEM-029).

**Безопасность:** запрет имени файла с `/`, `\` или `..` → 400 (без попытки «тихой» sanitize — CR изначально предполагал silent-strip, но это не даёт тестируемого 400 из сценария; выбрали явный reject), whitelist MIME по префиксу, проверка что итоговый путь внутри `attachments.dir` (defense-in-depth в `AttachmentStorageService`).

## Изменения в API
| Метод | Путь | Описание |
|-------|------|----------|
| POST | `/api/tasks/{id}/attachments` | multipart upload (kind=FILE) → 201 + metadata |
| POST | `/api/tasks/{id}/attachments/link` | JSON `{url, title}` (kind=LINK) → 201 |
| GET | `/api/tasks/{id}/attachments` | список метаданных |
| GET | `/api/tasks/{id}/attachments/{aid}/content` | стриминг файла (только FILE) |
| DELETE | `/api/tasks/{id}/attachments/{aid}` | удаление записи + файла |

## Изменения в схеме БД
Миграция `V20__task_attachments.java` (по паттерну V14/V15, Postgres + H2 fallback). Черновик CR называл её V16, но на момент реализации V16-V19 уже были заняты (agent_workspace_runs, mail_linking_pending_tasks, mail_linking_audit_fields, intake_gateway) — фактический следующий свободный номер оказался V20.
```sql
CREATE TABLE task_attachments (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    kind VARCHAR(8) NOT NULL,               -- FILE | LINK
    filename VARCHAR(255),                  -- FILE: оригинальное имя
    title VARCHAR(255),                     -- LINK: отображаемое имя
    url TEXT,                               -- LINK: внешний URL
    mime_type VARCHAR(127),
    file_size BIGINT,
    storage_ref TEXT,                       -- FILE: относительный путь
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_task_attachments_task_id ON task_attachments(task_id);
```

## Изменения в UI
Блок «Attachments» в правой control-панели task-edit (CR-MEM-023): список (иконка по kind, имя, размер), кнопки Upload / Add link / Delete.
**Реализовано без drag-drop зоны** — только `<input type="file">` + кнопка Upload (drag-drop не входил в MVP этого прохода, можно добавить отдельным follow-up).

Клик по FILE-вложению не переходит по прямой ссылке: JS сначала делает `HEAD` на `contentUrl`; если файл был удалён с диска вручную (404), показывается Bootstrap-модалка «Вложение недоступно» вместо навигации на Spring `Whitelabel Error Page` (обнаружено и исправлено по итогам ручного тестирования).

## Зависимости от других сервисов
Нет. (Follow-up идея: индексация текстовых вложений в RAG — отдельный CR-RAG.)

## Как тестировать
```
1. POST multipart PNG → 201, файл появился в workspace/attachments/{taskId}/
2. GET .../content → те же байты, Content-Type: image/png
3. POST /link с Drive URL → 201, kind=LINK
4. Upload файла с именем "../../evil.sh" → 400
5. Upload 25MB → 413
6. DELETE attachment → запись и файл удалены
7. DELETE задачи → каталог attachments/{taskId} очищен
   — НЕ РЕАЛИЗОВАНО: hard-delete задач в приложении отсутствует (см. выше), пункт неприменим как есть
8. Paste скриншота в Toast UI редактор → attachment создан, ![](…/content) вставлен в Markdown
   — ОТЛОЖЕНО: зависит от CR-MEM-029 (редактор), которая ещё не реализована
```

## Implementation Notes (2026-07-03)
- E2E покрытие: `test_e2e/23_task_attachments.md` (curl-сценарий, 9 шагов) + `test_e2e/tests/task-attachments.spec.js` (Playwright UI), оба PASS.
- Unit/integration: `TaskAttachmentControllerTest` (5 кейсов), полный набор проекта 134/134 PASS.
- RFC (`RFC/RFC-memory-service.md`) и `ARCHITECTURE.md` обновлены: новые endpoint'ы, таблица `memory.task_attachments`, файловая шина `workspace/attachments/`, control-panel секция #6.
