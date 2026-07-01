# 2026-06-30_CR-MEM-019: Intake Gateway — ручной входящий гейтвей

**Дата:** 2026-06-30  
**Статус:** Draft  
**Сервис:** MEM  
**Зависимости:** JavaMemoryService, JavaMailAgent, JavaRagService, common AgentClient, Global Search

## Проблема / Мотивация

Сейчас часть входящих сигналов может попадать в конечные хранилища слишком рано:

- `NOTICE` из почты может уходить в RAG без ручного контроля;
- Capture может классифицироваться агентом и маршрутизироваться без прозрачного ревью;
- Agent MCP может создавать знания/задачи без единого места аудита;
- пользователь не всегда видит исходный prompt и результат классификации агента;
- RAG постепенно превращается в свалку временной, сырой или ошибочно классифицированной информации.

Нужно ввести единый ручной gateway для всех входящих сигналов перед попаданием в RAG, задачи, заметки, инциденты, риски или карточки людей.

## Решение

Ввести в `JavaMemoryService` новый модуль **Intake Gateway**.

Intake Gateway — единая ручная очередь входящих кандидатов:

```text
Mail / Capture / Agent MCP / Manual
        ↓
AI preliminary classification
        ↓
JavaMemoryService Intake Gateway
        ↓
User Review
        ↓
Apply to target receiver
```

На первом этапе через Intake Gateway проходят только входящие автоматические сигналы, которым нужен ручной review перед финальной маршрутизацией.

Ручной пользовательский ввод в этот gateway не входит: если пользователь сам создает задачу, заметку, риск или другой объект вручную, он делает это напрямую в целевой сущности без дополнительной intake-очереди.

Для intake-элементов используется ручной review:

```text
NEW → REVIEWING → APPLIED | REJECTED
```

RAG становится не прямым приёмником `NOTICE`, а одним из доступных target receiver-ов.

## Основные источники первого этапа

| Источник | Как попадает в Intake Gateway |
|---------|-------------------------------|
| `MAIL` | JavaMailAgent отправляет результат классификации в MemoryService `/api/intake` |
| `CAPTURE` | Capture Bot после предварительной классификации создаёт intake item |

### Вне scope первого этапа

- `MANUAL`: ручной пользовательский ввод не проходит через Intake Gateway;
- `AGENT_MCP`: отдельный producer flow для Agent Workspace / MCP tools будет добавлен позже.

## Target receivers

Пользователь может перенаправить intake item в один из приёмников:

| Route | Действие при Apply |
|------|---------------------|
| `RAG` | после Apply записать knowledge markdown в `JavaRagService/rag-inbox/intake` |
| `TASK` | создать pending task |
| `NOTE` | создать operational note |
| `INCIDENT` | создать или обновить incident |
| `RISK` | создать или обновить risk |
| `PERSON` | создать person note / обновить карточку человека |
| `NOISE` | пометить как отклонённое / шум |

## Данные, которые обязательно храним

Каждый intake item должен сохранять полную трассировку:

```text
source_type        MAIL | CAPTURE | AGENT_MCP | MANUAL
source_id          внешний id письма/capture/run, если есть
source_payload     исходный текст/JSON
agent_provider     mock | ollama | gigachat | claude | codex | unknown
agent_prompt       prompt, отправленный агенту
agent_result       полный raw result агента
suggested_route    RAG | TASK | NOTE | INCIDENT | RISK | PERSON | NOISE
suggested_payload  структурированный payload, предложенный агентом
final_route        выбранный пользователем route
final_payload      payload после ручного редактирования
status             NEW | REVIEWING | APPLIED | REJECTED
confidence         confidence агента, если есть
created_at
reviewed_at
applied_at
created_by
reviewed_by
```

## UI

Добавить страницу:

```text
GET /ui/intake
```

Минимальный UI:

```text
Intake Gateway

Filters:
[Status: NEW/APPLIED/REJECTED] [Source: MAIL/CAPTURE/AGENT] [Suggested route]

Card:
- Source
- Created at
- Suggested route + confidence
- Original payload
- Agent prompt
- Agent result
- Editable final payload
- Target receiver selector

Actions:
[Apply]
[Reject]
[Save changes]
```

Важно: `agentPrompt` и `agentResult` должны быть видны пользователю, но свернуты по умолчанию.

На этом этапе UI предназначен для review уже созданных intake items. Отдельная форма ручного создания intake item пользователем не требуется.

## Изменения в API

### Создать intake item

```http
POST /api/intake
Content-Type: application/json
```

```json
{
  "sourceType": "MAIL",
  "sourceId": "<message-id>",
  "sourcePayload": "...",
  "agentProvider": "gigachat",
  "agentPrompt": "...",
  "agentResult": "...",
  "suggestedRoute": "RAG",
  "suggestedPayload": {
    "title": "...",
    "summary": "...",
    "tags": ["risk", "api"]
  },
  "confidence": 0.87
}
```

### Получить очередь

```http
GET /api/intake?status=NEW&sourceType=MAIL&suggestedRoute=RAG
```

### Получить элемент

```http
GET /api/intake/{id}
```

### Обновить финальный payload

```http
PUT /api/intake/{id}
```

### Применить

```http
POST /api/intake/{id}/apply
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

### Отклонить

```http
POST /api/intake/{id}/reject
```

```json
{
  "reason": "noise"
}
```

## Изменения в схеме БД

Новая таблица:

```sql
memory.intake_items
```

Минимальные поля:

```sql
id uuid primary key,
source_type varchar(32) not null,
source_id text,
source_payload_json jsonb,
source_text text,
agent_provider varchar(32),
agent_prompt text,
agent_result_json jsonb,
agent_result_text text,
suggested_route varchar(32),
suggested_payload_json jsonb,
final_route varchar(32),
final_payload_json jsonb,
status varchar(32) not null,
confidence numeric(5,4),
created_by varchar(128),
reviewed_by varchar(128),
created_at timestamp not null,
reviewed_at timestamp,
applied_at timestamp,
rejected_at timestamp,
reject_reason text
```

Индексы:

```sql
idx_intake_items_status_created_at
idx_intake_items_source_type_created_at
idx_intake_items_suggested_route_status
idx_intake_items_source_id
```

## Зависимости от других сервисов

### JavaMailAgent

- MailAgent отправляет intake item в MemoryService.
- `NOTICE` больше не должен напрямую писать файл в `rag-inbox`.
- В текущей реализации через Intake Gateway может идти не только `NOTICE`, но и другие классифицированные типы (`REQUEST`, `CAPTURE`, `NOTE`, `NOISE`) с suggested route.

### JavaRagService

- Не должен получать сырой `NOTICE` до ручного Apply.
- После ручного Apply knowledge-кандидат появляется в `rag-inbox/intake` и дальше обрабатывается существующим RAG ingestion flow.

### common

- Prompt builder должен возвращать достаточно структурированный result для сохранения `agentResult`.

## Как тестировать

### Unit / API

1. `POST /api/intake` создаёт item со статусом `NEW`.
2. `GET /api/intake?status=NEW` возвращает созданный item.
3. `PUT /api/intake/{id}` обновляет `finalPayload`.
4. `POST /api/intake/{id}/apply` создаёт нужную сущность и переводит item в `APPLIED`.
5. `POST /api/intake/{id}/reject` переводит item в `REJECTED`.

### Integration

1. Отправить письмо, классифицированное как `NOTICE`.
2. Проверить, что оно появилось в `/ui/intake`.
3. Проверить, что до Apply в `rag-inbox/intake` ничего не создано.
4. Нажать Apply → `RAG`.
5. Проверить, что markdown-файл появился в `JavaRagService/rag-inbox/intake`.

### UI smoke

1. `/ui/intake` открывается.
2. Карточка показывает source payload.
3. Prompt/result раскрываются.
4. Route можно поменять перед Apply.

## Acceptance Criteria

- [ ] Есть таблица `memory.intake_items`.
- [ ] Есть REST API `/api/intake/**`.
- [ ] Есть UI `/ui/intake`.
- [ ] Mail `NOTICE` попадает в Intake Gateway, а не напрямую в RAG.
- [ ] Пользователь видит `sourcePayload`, `agentPrompt`, `agentResult`, `suggestedRoute`.
- [ ] Пользователь может поменять route и применить.
- [ ] Apply создаёт сущность в выбранном target receiver.
- [ ] До ручного Apply knowledge markdown не попадает в `rag-inbox/intake`.

## Scope Notes

- Этот CR не добавляет ручное создание intake item через UI.
- Этот CR не добавляет отдельный producer flow для `AGENT_MCP`.
- Для `RAG` в рамках текущей реализации используется file-based handoff через `rag-inbox/intake`.

## После подтверждения пользователя

После подтверждения пользователя перевести этот CR в Статус: DONE и обновить реестр `docs/cr/REGISTRY.md`.
