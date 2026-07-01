# 2026-06-30_CR-MEM-021: Intake Rollback & Re-Apply — откат и пере-накат маршрутов

**Дата:** 2026-06-30  
**Статус:** Draft  
**Сервис:** MEM  
**Зависимости:** CR-MEM-019 Intake Gateway, CR-MEM-020 Route Automation, JavaMemoryService, JavaRagService

## Проблема / Мотивация

После ручного Apply или автоматического AUTO_APPLY пользователь может понять, что сигнал был отправлен не туда:

- письмо ушло в RAG, но оказалось временной заметкой;
- capture стал task, но должен был стать risk;
- agent MCP создал note, но её нужно привязать к человеку;
- AUTO_APPLY правило начало применять маршрут ошибочно;
- исходный payload был исправлен, и нужно пере-применить item.

Если нет отката и пере-накатки, Intake Gateway становится небезопасным: любое неверное применение создаёт мусор в целевых хранилищах, особенно в RAG.

## Решение

Добавить в Intake Gateway полноценный механизм **Application Ledger**:

```text
Intake Item
    ↓ Apply
Application Record
    ↓ creates / updates / links Target Entity
    ↓ can Rollback
    ↓ can Re-Apply with changed route/payload
```

Каждый Apply должен создавать запись применения с информацией:

- какой route применён;
- какая сущность создана или обновлена;
- какой payload использовался;
- кто применил;
- было ли это вручную или автоматически;
- как откатить изменение.

## Жизненный цикл

```text
NEW
  ↓ apply
APPLIED
  ↓ rollback
ROLLED_BACK
  ↓ re-apply
RE_APPLIED
```

Для AUTO_APPLY:

```text
AUTO_APPLIED
  ↓ rollback
ROLLED_BACK
  ↓ re-apply manually
RE_APPLIED
```

## Application Record

Новая сущность:

```text
intake_applications
```

Поля:

```text
id
intake_item_id
application_no
route
payload_json
mode                 MANUAL | AUTO
status               APPLIED | ROLLED_BACK | FAILED
operation_type       CREATE | UPDATE | LINK | MERGE | REJECT
receiver_type        RAG | TASK | NOTE | INCIDENT | RISK | PERSON | NOISE
receiver_entity_id
receiver_entity_ref
rollback_strategy    DELETE_CREATED | RESTORE_PREVIOUS | UNLINK | MARK_SUPERSEDED | MANUAL_ONLY
rollback_payload_json
applied_by
applied_at
rolled_back_by
rolled_back_at
rollback_reason
error_message
```

## Rollback стратегии

| Strategy | Когда применять | Действие |
|---------|-----------------|----------|
| `DELETE_CREATED` | создана новая сущность | удалить/архивировать созданную сущность |
| `RESTORE_PREVIOUS` | обновлена существующая сущность | восстановить snapshot до изменения |
| `UNLINK` | создана связь | удалить связь |
| `MARK_SUPERSEDED` | RAG document уже мог быть проиндексирован | пометить документ superseded/outdated и убрать из активного поиска |
| `MANUAL_ONLY` | операция небезопасна для автоотката | показать пользователю инструкцию ручного отката |

## Особенности для RAG

RAG нельзя рассматривать как простое CRUD-хранилище, потому что документ может быть уже проиндексирован в OpenSearch.

Для RAG route rollback должен:

1. Найти knowledge document, созданный из intake application.
2. Пометить документ как `SUPERSEDED` или `ROLLBACK_REQUESTED`.
3. Вызвать переиндексацию / удаление чанков из OpenSearch.
4. Обновить `rag.indexed_documents` или соответствующий статус через API JavaRagService.
5. Сохранить ссылку на исходный intake item.

На первом этапе допустимо реализовать безопасный MVP:

```text
Rollback RAG = mark document as superseded + exclude from search results
```

Физическое удаление чанков можно сделать отдельным BUGFIX/CR.

## Re-Apply

После rollback пользователь может изменить route/payload и применить заново:

```text
APPLIED item
    ↓ Rollback
ROLLED_BACK item
    ↓ Edit finalRoute/finalPayload
    ↓ Re-Apply
RE_APPLIED item
```

Re-Apply создаёт новую запись `intake_applications` с увеличенным `application_no`.

Старые application records не удаляются.

## Изменения в API

### История применений

```http
GET /api/intake/{id}/applications
```

### Rollback

```http
POST /api/intake/{id}/applications/{applicationId}/rollback
```

```json
{
  "reason": "Wrong route: should be RISK instead of RAG"
}
```

### Re-Apply

```http
POST /api/intake/{id}/reapply
```

```json
{
  "finalRoute": "RISK",
  "finalPayload": {
    "title": "...",
    "description": "...",
    "riskNumber": "RISK-42"
  }
}
```

### Preview rollback

```http
POST /api/intake/{id}/applications/{applicationId}/rollback-preview
```

Возвращает:

```json
{
  "safe": true,
  "strategy": "MARK_SUPERSEDED",
  "affectedEntities": [
    {
      "type": "RAG_DOCUMENT",
      "id": "...",
      "action": "MARK_SUPERSEDED"
    }
  ]
}
```

## Изменения в UI

На странице `/ui/intake` добавить:

### History panel

```text
Applications:
#1 APPLIED route=RAG receiver=DOC-123 mode=MANUAL
#2 ROLLED_BACK reason="wrong route"
#3 RE_APPLIED route=RISK receiver=RISK-42 mode=MANUAL
```

### Actions

Для `APPLIED` / `AUTO_APPLIED`:

```text
[Rollback]
[Rollback & Re-Apply]
```

Для `ROLLED_BACK`:

```text
[Edit payload]
[Re-Apply]
```

Перед rollback UI должен показать preview:

```text
Будет выполнено:
- RAG document DOC-123 → MARK_SUPERSEDED
- OpenSearch chunks → exclude from active search
```

## Изменения в схеме БД

Новая таблица:

```sql
memory.intake_applications
```

```sql
id uuid primary key,
intake_item_id uuid not null,
application_no int not null,
route varchar(32) not null,
payload_json jsonb not null,
mode varchar(32) not null,
status varchar(32) not null,
operation_type varchar(32) not null,
receiver_type varchar(32) not null,
receiver_entity_id text,
receiver_entity_ref text,
rollback_strategy varchar(32) not null,
rollback_payload_json jsonb,
applied_by varchar(128),
applied_at timestamp not null,
rolled_back_by varchar(128),
rolled_back_at timestamp,
rollback_reason text,
error_message text
```

Индексы:

```sql
idx_intake_applications_item_id
idx_intake_applications_receiver
idx_intake_applications_status
```

Добавить поля в `memory.intake_items`:

```sql
last_application_id uuid,
apply_count int not null default 0,
rollback_count int not null default 0
```

## Связь с автоматизацией маршрутов

Если AUTO_APPLY item был откатан, нужно обновить статистику правила:

```text
intake_route_rules.failure_count += 1
```

Если failure_count превышает порог, правило должно быть автоматически понижено:

```text
AUTO_APPLY → SUGGEST_ONLY
```

Предлагаемый дефолт:

```text
failure_count >= 3 за последние 20 применений → downgrade
```

UI должен показать предупреждение:

```text
Правило Risk mails downgraded to SUGGEST_ONLY после 3 rollback.
```

## Как тестировать

### Manual apply rollback

1. Создать intake item.
2. Apply в `NOTE`.
3. Проверить, что note создана.
4. Rollback.
5. Проверить, что note удалена/архивирована.
6. Проверить application history.

### Re-Apply

1. Создать intake item.
2. Apply в `RAG`.
3. Rollback.
4. Re-Apply в `RISK`.
5. Проверить, что application history содержит две application records.

### AUTO_APPLY rollback impact

1. Создать AUTO_APPLY rule.
2. Создать matching item.
3. Проверить `AUTO_APPLIED`.
4. Rollback.
5. Проверить, что `failure_count` правила увеличился.

### RAG rollback MVP

1. Apply item в `RAG`.
2. Проверить, что документ доступен в knowledge search.
3. Rollback.
4. Проверить, что документ помечен как superseded/outdated и не возвращается в active search.

## Acceptance Criteria

- [ ] Каждый Apply создаёт запись в `memory.intake_applications`.
- [ ] Есть API rollback-preview.
- [ ] Есть API rollback.
- [ ] Есть API reapply.
- [ ] UI показывает историю применений.
- [ ] Пользователь может откатить ошибочный Apply.
- [ ] Пользователь может пере-применить item в другой route.
- [ ] Rollback AUTO_APPLY влияет на статистику route rule.
- [ ] Для RAG реализован безопасный rollback MVP через `SUPERSEDED` / exclude from active search.

## После подтверждения пользователя

После подтверждения пользователя перевести этот CR в Статус: DONE и обновить реестр `docs/cr/REGISTRY.md`.
