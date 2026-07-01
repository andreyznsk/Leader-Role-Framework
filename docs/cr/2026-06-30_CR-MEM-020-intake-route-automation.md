# 2026-06-30_CR-MEM-020: Intake Route Automation — автоматизация маршрутов

**Дата:** 2026-06-30  
**Статус:** Draft  
**Сервис:** MEM  
**Зависимости:** CR-MEM-019 Intake Gateway, JavaMemoryService, JavaMailAgent, common AgentClient

## Проблема / Мотивация

После появления Intake Gateway все входящие сигналы сначала проходят ручное ревью. Это безопасно, но при постоянных повторяющихся маршрутах пользователь будет выполнять однотипные действия:

- письма от определённого отправителя всегда становятся `RISK`;
- capture с определённым паттерном всегда становится `PERSON` note;
- agent MCP предложения одного типа всегда уходят в `NOTE`;
- часть `NOTICE` всегда отклоняется как `NOISE`.

Нужно дать пользователю возможность постепенно включать автоматизацию только для проверенных маршрутов.

## Решение

Ввести **route automation rules** для Intake Gateway.

Автоматизация должна развиваться по ступеням:

```text
MANUAL → SUGGEST_ONLY → AUTO_APPLY
```

### MANUAL

Правило не применяет маршрут автоматически. Item создаётся в Intake Gateway со статусом `NEW`.

### SUGGEST_ONLY

Правило подставляет recommended final route / payload, но пользователь всё равно нажимает Apply.

### AUTO_APPLY

Правило автоматически применяет route, создаёт target entity и оставляет audit trail. Item должен быть виден в истории `/ui/intake` со статусом `AUTO_APPLIED`.

## Когда правило можно включить

Пользователь может создать правило вручную или из успешного intake item:

```text
[Сделать этот маршрут автоматическим]
```

Перед созданием правила UI должен показать условия:

```text
Source: MAIL
Sender: *@domain.ru
Suggested route: RISK
Final route: RISK
Min confidence: 0.85
```

## Rule Model

Новая сущность:

```text
intake_route_rules
```

Поля:

```text
id
name
enabled
mode                 MANUAL | SUGGEST_ONLY | AUTO_APPLY
priority
source_type          MAIL | CAPTURE | AGENT_MCP | MANUAL | ANY
match_conditions     JSONB
route_conditions     JSONB
final_route
final_payload_patch  JSONB
min_confidence
success_count
failure_count
created_from_item_id
created_at
updated_at
last_matched_at
```

Примеры условий:

```json
{
  "sourceType": "MAIL",
  "senderContains": "risk",
  "subjectContains": "риск",
  "suggestedRoute": "RISK",
  "minConfidence": 0.85
}
```

```json
{
  "sourceType": "CAPTURE",
  "textContainsAny": ["запомни", "по человеку", "one-to-one"],
  "suggestedRoute": "PERSON"
}
```

## Применение правил

Flow после создания intake item:

```text
Create Intake Item
        ↓
Find matching enabled rules ordered by priority
        ↓
No rule → status NEW
        ↓
Rule mode SUGGEST_ONLY → set recommended rule, status NEW
        ↓
Rule mode AUTO_APPLY → apply final route, status AUTO_APPLIED
```

Если несколько правил совпали:

1. Берётся правило с максимальным `priority`.
2. При равном priority — более специфичное правило.
3. При конфликте — item остаётся `NEW`, а UI показывает conflict warning.

## Безопасность AUTO_APPLY

AUTO_APPLY нельзя включать для потенциально опасных операций без ручного подтверждения:

- изменение существующего incident/risk/task;
- merge/link с существующей сущностью;
- удаление или hard reject;
- массовая обработка batch.

Для таких операций разрешён только `SUGGEST_ONLY` на первом этапе.

AUTO_APPLY разрешён для:

- создание новой `NOTE`;
- создание новой `PERSON_NOTE`;
- создание новой `TASK` / `PENDING_TASK`;
- создание нового `RAG` candidate только если CR-MEM-019 уже ввёл ручной knowledge apply policy;
- `NOISE`, если правило явно создано пользователем.

## Изменения в API

### Список правил

```http
GET /api/intake/rules
```

### Создать правило

```http
POST /api/intake/rules
```

### Создать правило из item

```http
POST /api/intake/{id}/create-rule
```

```json
{
  "name": "Risk mails from domain",
  "mode": "SUGGEST_ONLY",
  "conditions": {
    "sourceType": "MAIL",
    "senderDomain": "domain.ru",
    "suggestedRoute": "RISK"
  },
  "finalRoute": "RISK",
  "minConfidence": 0.85
}
```

### Обновить правило

```http
PUT /api/intake/rules/{ruleId}
```

### Включить / выключить

```http
PATCH /api/intake/rules/{ruleId}/state
```

```json
{
  "enabled": true
}
```

### Dry-run

```http
POST /api/intake/rules/{ruleId}/dry-run
```

Dry-run должен показать, какие последние intake items правило бы обработало.

## Изменения в UI

Добавить вкладку на `/ui/intake`:

```text
Intake Gateway
- Queue
- History
- Rules
```

### Queue

В карточке item показывать:

```text
Matched rule: Risk mails from domain
Mode: SUGGEST_ONLY
Recommended final route: RISK
```

### Rules

Таблица правил:

```text
Enabled | Priority | Name | Mode | Source | Final Route | Success | Failures | Last matched
```

Действия:

```text
[Enable/Disable]
[Edit]
[Dry-run]
[Promote to AUTO]
[Downgrade to SUGGEST_ONLY]
```

## Изменения в схеме БД

Новая таблица:

```sql
memory.intake_route_rules
```

```sql
id uuid primary key,
name varchar(256) not null,
enabled boolean not null default true,
mode varchar(32) not null,
priority int not null default 100,
source_type varchar(32) not null default 'ANY',
match_conditions_json jsonb not null,
route_conditions_json jsonb,
final_route varchar(32) not null,
final_payload_patch_json jsonb,
min_confidence numeric(5,4),
success_count bigint not null default 0,
failure_count bigint not null default 0,
created_from_item_id uuid,
created_at timestamp not null,
updated_at timestamp not null,
last_matched_at timestamp
```

Добавить поля в `memory.intake_items`:

```sql
matched_rule_id uuid null,
automation_mode varchar(32),
auto_applied boolean not null default false,
auto_apply_error text
```

## Как тестировать

### Rule matching

1. Создать intake item без правил → статус `NEW`.
2. Создать правило `SUGGEST_ONLY`.
3. Создать matching item → item остаётся `NEW`, но имеет `matchedRuleId`.
4. Создать правило `AUTO_APPLY`.
5. Создать matching item → item становится `AUTO_APPLIED`, target entity создана.

### Conflict handling

1. Создать два правила с одинаковым priority и разными finalRoute.
2. Создать matching item.
3. Проверить, что item не auto-applied и содержит conflict warning.

### Dry-run

1. Создать правило.
2. Запустить dry-run.
3. Проверить, что API возвращает список matched historical items без изменения статусов.

## Acceptance Criteria

- [ ] Есть таблица `memory.intake_route_rules`.
- [ ] Есть API `/api/intake/rules/**`.
- [ ] Пользователь может создать правило из successful item.
- [ ] Поддержаны режимы `MANUAL`, `SUGGEST_ONLY`, `AUTO_APPLY`.
- [ ] AUTO_APPLY создаёт target entity и оставляет audit trail.
- [ ] Конфликт правил не приводит к автоматическому применению.
- [ ] UI показывает matched rule и режим автоматизации.
- [ ] Есть dry-run правила.

## После подтверждения пользователя

После подтверждения пользователя перевести этот CR в Статус: DONE и обновить реестр `docs/cr/REGISTRY.md`.
