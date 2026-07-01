# 2026-07-01_CR-MEM-024: Agent MCP write-tools via Intake Gateway

**Дата:** 2026-07-01  
**Статус:** Implemented  
**Сервис:** MEM  
**Тип:** enhancement / architecture / mcp-safety  
**Связанная задача:** https://github.com/andreyznsk/Leader-Role-Framework/issues/57

## Проблема / Мотивация

Сейчас агентские MCP tools умеют не только читать данные, но и напрямую создавать или изменять сущности в JavaMemoryService.

Проблема:

- `createTask`, `addRisk`, `createIncident` и аналогичные write-tools могут создавать сущности напрямую;
- пользователь не видит предварительное предложение агента до записи;
- отсутствует единая точка review для agent-originated writes;
- Intake Gateway уже существует как правильная прослойка подтверждения, но agent-originated MCP writes пока обходят её.

Целевой принцип:

```text
AI Agent не пишет напрямую в operational memory.
AI Agent формирует proposal.
Пользователь подтверждает proposal через /ui/intake.
```

Это соответствует архитектурному правилу LeaderOS: агент предлагает, человек подтверждает, MemoryService остаётся контролируемой операционной памятью.

## Решение

Ввести жёсткий режим для agent-originated MCP write operations:

```text
Agent MCP tool
    → proposeTask / proposeRisk / proposeIncident / ...
    → AgentIntakeProposalService
    → IntakeService.create(...)
    → /ui/intake
    → пользователь review/edit/apply
    → IntakeTargetApplier
    → Task / Risk / Incident / Note / Knowledge / Person
```

Read tools остаются как есть:

- `getContext`
- `getTasks`
- `getRisks`
- `getPeople`
- `searchKnowledge`

Write tools, доступные агенту через MCP, должны стать proposal tools:

| Сейчас | Должно быть |
|--------|-------------|
| `createTask` | `proposeTask` |
| `addRisk` / `createRisk` | `proposeRisk` |
| `createIncident` | `proposeIncident` |
| `addPeopleNote` | `proposePersonNote` |
| будущий `createNote` | `proposeNote` |
| будущий `createKnowledge` | `proposeKnowledge` |

Прямой internal/service-level write path можно оставить для UI, REST API и внутренних сервисов, но MCP-контракт агента не должен использовать прямое создание сущностей.

## Архитектурный flow

```mermaid
flowchart TD
  A[AI Agent / MCP] --> B{Tool type}
  B -->|read| R[Read tools без изменений]
  B -->|write| P[Proposal tools]

  P --> S[AgentIntakeProposalService]
  S --> I[IntakeService.create]
  I --> UI[/ui/intake]
  UI --> E[User review/edit]
  E --> AP[IntakeTargetApplier]
  AP --> T[Task / Risk / Incident / Note / RAG / Person]

  style P fill:#2D1B1B,stroke:#F78166,color:#F78166
  style S fill:#1B1B2D,stroke:#4A9EFF,color:#4A9EFF
  style I fill:#1B2D2A,stroke:#00D4AA,color:#00D4AA
  style UI fill:#1B2D2A,stroke:#00D4AA,color:#00D4AA
```

## Изменения в API

### MCP tools

Добавить или заменить agent-facing tools:

#### `proposeTask`

Создаёт intake item с `suggestedRoute = TASK`.

Минимальные поля:

- `title`
- `description`
- `priority`
- `date` / `dueDate` при наличии
- `sourceId` / `runId` / `sessionId` при наличии

#### `proposeRisk`

Создаёт intake item с `suggestedRoute = RISK`.

Минимальные поля:

- `title`
- `description`
- `probability`
- `impact`
- `mitigation` при наличии

#### `proposeIncident`

Создаёт intake item с `suggestedRoute = INCIDENT`.

Минимальные поля:

- `title`
- `description`
- `severity`
- `status` при наличии

#### `proposeNote`

Создаёт intake item с `suggestedRoute = NOTE`.

#### `proposeKnowledge`

Создаёт intake item с `suggestedRoute = RAG`.

#### `proposePersonNote`

Создаёт intake item с `suggestedRoute = PERSON`.

### Existing API

`/api/intake`, `/ui/intake` и `IntakeTargetApplier` должны использоваться без изменения основного apply-flow.

## Рекомендуемый helper/service

Добавить сервис:

```text
JavaMemoryService
└── AgentIntakeProposalService
```

Ответственность:

- принимать нормализованный input от MCP tools;
- собирать `IntakeCreateRequest`;
- заполнять agent/source metadata;
- вызывать `IntakeService.create(...)`;
- возвращать агенту понятный результат: proposal создан, требуется подтверждение в `/ui/intake`.

Пример методов:

```java
createTaskProposal(...)
createRiskProposal(...)
createIncidentProposal(...)
createNoteProposal(...)
createKnowledgeProposal(...)
createPersonNoteProposal(...)
```

## Payload contract

### Task proposal

```json
{
  "sourceType": "AGENT_MCP",
  "sourceId": "run-123",
  "sourcePayload": {
    "tool": "proposeTask",
    "title": "Подготовить rollout plan",
    "date": "2026-07-01",
    "priority": "HIGH",
    "description": "..."
  },
  "agentProvider": "codex",
  "agentPrompt": "optional prompt/run context",
  "agentResult": "optional structured proposal or raw model output",
  "suggestedRoute": "TASK",
  "suggestedPayload": {
    "title": "Подготовить rollout plan",
    "description": "...",
    "priority": "HIGH"
  },
  "createdBy": "agent-mcp"
}
```

### Risk proposal

```json
{
  "sourceType": "AGENT_MCP",
  "sourceId": "run-123",
  "sourcePayload": {
    "tool": "proposeRisk",
    "title": "Единственный владелец деплоя",
    "description": "Только один человек знает production deploy flow"
  },
  "agentProvider": "codex",
  "suggestedRoute": "RISK",
  "suggestedPayload": {
    "title": "Единственный владелец деплоя",
    "description": "Только один человек знает production deploy flow",
    "probability": "MEDIUM",
    "impact": "HIGH"
  },
  "createdBy": "agent-mcp"
}
```

## Изменения в схеме БД

Ожидаемо миграции не нужны, если текущая модель intake уже поддерживает:

- `sourceType`
- `sourceId`
- `sourcePayload`
- `agentProvider`
- `agentPrompt`
- `agentResult`
- `suggestedRoute`
- `suggestedPayload`
- `createdBy`

Если каких-то полей нет, не менять существующие миграции в `*/db/migration`. Нужно добавить новую миграцию отдельным файлом и явно зафиксировать это в отчёте реализации.

## Зависимости

- JavaMemoryService Intake Gateway
- Existing `/ui/intake`
- Existing `IntakeTargetApplier`
- MCP tools JavaMemoryService
- Agent Workspace / agent run metadata при наличии

## Изменения в коде

Минимальный practical scope:

1. Добавить `AgentIntakeProposalService`.
2. В `TaskTools.java` заменить agent-facing direct write path на proposal через intake.
3. В `RiskTools.java` заменить agent-facing direct write path на proposal через intake.
4. В `IncidentTools.java` заменить agent-facing direct write path на proposal через intake.
5. Если есть write tools для notes/knowledge/people — перевести их на proposal через intake.
6. Обновить MCP tool names/descriptions так, чтобы агент понимал: это proposal, а не прямое создание.
7. Возвращать агенту текст/DTO вида:

```text
Proposal created in Intake Gateway. Open /ui/intake to review and apply.
```

## Важные правила реализации

- Жёсткий режим: все agent-originated writes идут только через intake.
- Не ломать UI/REST прямое создание задач пользователем.
- Не менять существующие Flyway migration-файлы.
- Не переписывать `IntakeTargetApplier`, если текущий apply-flow уже покрывает нужные target routes.
- Не давать агенту публичный MCP-инструмент, который напрямую пишет `Task/Risk/Incident`.
- Read tools оставить без изменений.

## Как тестировать

Добавить E2E сценарии в `JavaMemoryService/test_e2e/`.

### Scenario 1 — proposeTask creates intake item

```text
MCP/HTTP test invokes proposeTask
    → intake item created with sourceType = AGENT_MCP
    → status = NEW
    → suggestedRoute = TASK
    → suggestedPayload.title matches input
```

### Scenario 2 — apply task proposal

```text
proposeTask
    → GET /api/intake?status=NEW
    → find created proposal
    → POST /api/intake/{id}/apply
    → task exists in /api/tasks
```

### Scenario 3 — proposeRisk creates intake item

```text
proposeRisk
    → intake item created with sourceType = AGENT_MCP
    → suggestedRoute = RISK
```

### Scenario 4 — proposeIncident creates intake item

```text
proposeIncident
    → intake item created with sourceType = AGENT_MCP
    → suggestedRoute = INCIDENT
```

### Regression

Проверить, что:

- пользовательский `POST /api/tasks` всё ещё создаёт задачу напрямую;
- `/ui/intake` открывается и показывает agent proposal;
- `getContext`, `getTasks`, `getRisks`, `searchKnowledge` работают как раньше.

## Acceptance Criteria

- [x] Agent-facing MCP write tools больше не создают Task/Risk/Incident напрямую.
- [x] Добавлены proposal tools: `proposeTask`, `proposeRisk`, `proposeIncident`.
- [x] При необходимости добавлены proposal tools для доступных agent write-cases; в текущем scope добавлен `proposePersonNote`.
- [x] Proposal tools создают intake item через `IntakeService.create(...)`.
- [x] У intake item выставляется `sourceType = AGENT_MCP`.
- [x] `sourcePayload` содержит raw tool input.
- [x] `suggestedRoute` корректно выставляется в `TASK | RISK | INCIDENT | NOTE | RAG | PERSON`.
- [x] `suggestedPayload` содержит нормализованный payload для Apply.
- [x] `/ui/intake` показывает agent proposal.
- [x] Пользователь может изменить route/payload и нажать Apply.
- [x] Existing `IntakeTargetApplier` создаёт целевую сущность после Apply.
- [x] Добавлены E2E сценарии на proposal → intake → apply.
- [x] Обновлены RFC/ARCHITECTURE.md после реализации.
- [x] CR после подтверждения пользователя переведён в статус `Implemented`, реестр `docs/cr/REGISTRY.md` обновлён.

## Definition of Done

- Код реализован.
- E2E тесты добавлены и проходят.
- Нет прямого agent-facing MCP write path в operational entities.
- Документация актуализирована.
- Issue #57 содержит ссылку на этот CR.
