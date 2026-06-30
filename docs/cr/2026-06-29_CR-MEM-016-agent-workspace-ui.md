# 2026-06-29_CR-MEM-016: Agent Workspace UI

_Переименован из CR-MEM-012 для устранения коллизии номеров, 2026-06-30._

**Дата:** 2026-06-29  
**Статус:** Implemented  
**Сервис:** MEM  
**Компонент:** JavaMemoryService / UI / Agent Runtime Bridge  
**Связанный Issue:** будет создан после коммита CR

## Проблема / Мотивация

В LeaderOS уже есть несколько автоматических сценариев, где Java-сервисы вызывают AI-агента через `common::AgentClient` и получают результат в неинтерактивном режиме.

Однако пользователю не хватает отдельного UI-места для ручной работы с агентом:

- быстро задать вопрос агенту из браузера;
- проверить, как работает `AgentClient` и выбранный provider;
- вручную прогнать prompt без перехода в терминал;
- увидеть поток выполнения агентской сессии в формате консоли;
- в будущем отлаживать MCP/tool calls и AgentRuntime.

Сейчас такой интерфейс отсутствует. Работа с агентом происходит либо фоново внутри сервисов, либо через локальный CLI.

## Решение

Добавить в JavaMemoryService новую страницу:

```text
/ui/agent-workspace
```

Страница должна стать единым UI для ручной работы с AI-агентом и иметь два режима:

1. **Chat mode** — один prompt → один ответ через `AgentClient.complete(prompt)`.
2. **Console mode** — псевдоконсоль через WebSocket, где Java запускает агентский процесс и проксирует stdin/stdout/stderr в браузер.

На первом этапе не использовать `xterm.js`. Реализовать простую браузерную консоль на HTML/CSS/JavaScript + WebSocket. Это позволит быстро проверить backend-механику интерактивного запуска агента. В будущем можно заменить визуальный слой на `xterm.js`, не меняя backend-протокол.

## UX / UI

### Навигация

Добавить пункт меню в общий UI JavaMemoryService:

```text
Agent Workspace
```

### Страница `/ui/agent-workspace`

Страница содержит две вкладки или переключатель режимов:

```text
[ Chat ] [ Console ]
```

### Chat mode

Форма:

```text
Provider: claude | ollama | gigachat | mock
Prompt textarea
[Run]
```

Результат:

```text
Status: SUCCESS | ERROR
Duration
Provider
Response body
```

Поведение:

```text
Browser
  → POST /api/agent/chat/run
  → JavaMemoryService
  → AgentClient.complete(prompt)
  → claude --print / ollama / gigachat / mock
  → response
```

### Console mode

Простая консоль:

```text
--------------------------------------------------
Agent Console
--------------------------------------------------
12:30:01 session started
12:30:03 agent> ...
12:30:05 stdout> ...
--------------------------------------------------
[input command]
[Send] [Stop]
```

Поведение:

```text
Browser
  ↔ WebSocket /ws/agent-console
  ↔ JavaMemoryService
  ↔ ProcessBuilder("claude")
```

JavaMemoryService должен:

- открыть управляемую agent-сессию;
- запустить только разрешённую команду агента;
- проксировать stdout/stderr в браузер;
- принимать input из браузера и писать его в stdin процесса;
- позволять остановить процесс кнопкой `Stop`;
- закрывать процесс при закрытии WebSocket-соединения.

## Безопасность

Не давать браузеру доступ к shell.

Запрещено запускать:

```text
bash
sh
zsh
powershell
cmd
```

Разрешённые команды задаются конфигурацией, например:

```yaml
agentWorkspace:
  console:
    enabled: true
    allowedCommands:
      - claude
      - codex
      - gigacode
```

Для MVP можно оставить только:

```yaml
allowedCommands:
  - claude
```

Console mode должен запускать не произвольную команду пользователя, а выбранный из whitelist agent runtime.

## Изменения в API

### REST API

#### `POST /api/agent/chat/run`

Запускает один prompt через `AgentClient.complete(prompt)`.

Request:

```json
{
  "prompt": "Прочитай ARCHITECTURE.md и кратко объясни архитектуру",
  "provider": "claude",
  "includeContext": false
}
```

Response:

```json
{
  "status": "SUCCESS",
  "provider": "claude",
  "durationMs": 8123,
  "response": "...",
  "error": null
}
```

При ошибке:

```json
{
  "status": "ERROR",
  "provider": "claude",
  "durationMs": 1200,
  "response": null,
  "error": "..."
}
```

### WebSocket API

Endpoint:

```text
/ws/agent-console
```

Client → Server messages:

```json
{ "type": "START", "provider": "claude" }
{ "type": "STDIN", "data": "Прочитай AGENT.md\n" }
{ "type": "STOP" }
```

Server → Client messages:

```json
{ "type": "SESSION_STARTED", "sessionId": "..." }
{ "type": "STDOUT", "data": "..." }
{ "type": "STDERR", "data": "..." }
{ "type": "SESSION_STOPPED", "exitCode": 0 }
{ "type": "ERROR", "message": "..." }
```

## Изменения в схеме БД

Для MVP БД можно не менять, но желательно сразу добавить аудит agent runs.

Новая таблица:

```sql
CREATE TABLE memory.agent_workspace_runs (
    id UUID PRIMARY KEY,
    mode VARCHAR(32) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    prompt TEXT,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

Режимы:

```text
CHAT
CONSOLE
```

Статусы:

```text
STARTED
SUCCESS
ERROR
STOPPED
```

## Основные классы / компоненты

Предлагаемая структура:

```text
JavaMemoryService
└── agentworkspace
    ├── AgentWorkspacePageController
    ├── AgentChatRestController
    ├── AgentConsoleWebSocketConfig
    ├── AgentConsoleWebSocketHandler
    ├── AgentWorkspaceService
    ├── AgentProcessRunner
    ├── AgentWorkspaceRunRepository
    ├── dto
    │   ├── AgentChatRunRequest
    │   ├── AgentChatRunResponse
    │   ├── AgentConsoleClientMessage
    │   └── AgentConsoleServerMessage
    └── model
        └── AgentWorkspaceRun
```

UI template:

```text
JavaMemoryService/src/main/resources/templates/agent-workspace.html
```

Static JS/CSS при необходимости:

```text
JavaMemoryService/src/main/resources/static/js/agent-workspace.js
JavaMemoryService/src/main/resources/static/css/agent-workspace.css
```

## Зависимости

Для WebSocket добавить Spring dependency, если её ещё нет в JavaMemoryService:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-websocket</artifactId>
</dependency>
```

`xterm.js` в этом CR не подключать.

## Acceptance Criteria

### Chat mode

- [ ] В UI доступна страница `/ui/agent-workspace`.
- [ ] На странице есть вкладка/режим `Chat`.
- [ ] Пользователь может ввести prompt и нажать `Run`.
- [ ] Backend вызывает `AgentClient.complete(prompt)`.
- [ ] UI показывает ответ агента.
- [ ] UI показывает ошибку, если agent provider недоступен.
- [ ] Запуск фиксируется в `memory.agent_workspace_runs` или в логах, если БД-аудит отложен.

### Console mode

- [ ] На странице есть вкладка/режим `Console`.
- [ ] Browser открывает WebSocket `/ws/agent-console`.
- [ ] Backend может запустить разрешённый agent process через `ProcessBuilder`.
- [ ] stdout/stderr процесса отображаются в UI потоково.
- [ ] Пользователь может отправить строку input в stdin процесса.
- [ ] Кнопка `Stop` завершает процесс.
- [ ] Закрытие WebSocket завершает процесс.
- [ ] Browser не может запускать произвольные shell-команды.

### Safety

- [ ] Команда agent runtime выбирается только из whitelist.
- [ ] Shell (`bash`, `sh`, `zsh`, `cmd`, `powershell`) не запускается.
- [ ] Одновременно разрешена максимум одна active console session на пользователя/браузерную сессию для MVP.
- [ ] Ошибки процесса не ломают UI и отображаются пользователю.

## Как тестировать

### Unit / integration checks

1. Запустить JavaMemoryService:

```bash
SPRING_PROFILES_ACTIVE=local java -jar JavaMemoryService/target/memory-service.jar
```

2. Открыть страницу:

```text
http://localhost:8082/ui/agent-workspace
```

3. Проверить Chat mode с `mock` provider:

```text
Prompt: ping
Expected: ответ mock provider, без ошибки
```

4. Проверить Chat mode с `claude`, если CLI установлен:

```text
Prompt: Скажи одним предложением, что такое LeaderOS
Expected: SUCCESS, непустой response
```

5. Проверить Console mode:

```text
Open Console → Start → отправить строку → увидеть stdout/stderr поток
```

6. Проверить Stop:

```text
Start session → Stop → процесс завершён, UI показывает SESSION_STOPPED
```

7. Проверить безопасность:

```text
Попытка передать неразрешённую command должна вернуть ERROR.
```

## Future / Not in scope

Не входит в этот CR:

- подключение `xterm.js`;
- полноценный AI Runtime / AI Hub;
- долговременная память интерактивной agent-сессии;
- отображение MCP/tool calls как структурированных событий;
- role-based access control;
- multi-user session isolation beyond MVP.

Эти пункты можно оформить отдельными CR после проверки MVP.
