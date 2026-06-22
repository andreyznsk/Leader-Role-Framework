# CR-MAIL-004: Mail Plugin Control API

**Дата:** 2026-06-22  
**Статус:** Draft  
**Сервис:** MAIL  
**Зависимости:** CR-MEM-010, JavaMemoryService

## Проблема / Мотивация

`JavaMailAgent` является отдельным Java-процессом. Его нельзя напрямую "настроить" через форму в MemoryService, если внутри MailAgent нет собственного runtime API для чтения и применения настроек.

Нужен универсальный контракт для подключенных plugin-сервисов:

```text
/plugin-service/api/control/settings
```

MemoryService будет использовать этот контракт как control plane:

1. Запрашивает у MailAgent список доступных настроек.
2. Рендерит универсальный UI по key-value descriptor.
3. Пользователь меняет настройки.
4. MemoryService отправляет изменения в MailAgent.
5. MailAgent применяет изменения в runtime.
6. MailAgent пишет в логи, какие настройки применились.

## Решение

Добавить в `JavaMailAgent` новый REST API:

```text
/api/control/settings
/api/control/status
/api/control/audit
```

Этот API нужен не пользователю напрямую, а `JavaMemoryService`.

## API

### Получить descriptor настроек MailAgent

```http
GET /api/control/settings
```

Response:

```json
{
  "pluginCode": "mail",
  "pluginName": "Mail Agent",
  "version": 1,
  "settings": {
    "enabled": {
      "value": "false",
      "type": "boolean",
      "label": "Enabled",
      "description": "Enable or disable mail polling without stopping JVM process",
      "editable": true,
      "secret": false,
      "required": true
    },
    "protocol": {
      "value": "maildev",
      "type": "select",
      "label": "Protocol",
      "description": "Mail protocol/client implementation",
      "options": ["maildev", "imap", "ews"],
      "editable": true,
      "secret": false,
      "required": true
    },
    "login": {
      "value": "",
      "type": "string",
      "label": "Login",
      "editable": true,
      "secret": false,
      "required": false
    },
    "password": {
      "value": "********",
      "type": "secret",
      "label": "Password / secret",
      "editable": true,
      "secret": true,
      "required": false
    },
    "serverUrl": {
      "value": "",
      "type": "string",
      "label": "Server URL",
      "editable": true,
      "secret": false,
      "required": false
    },
    "host": {
      "value": "",
      "type": "string",
      "label": "Host",
      "editable": true,
      "secret": false,
      "required": false
    },
    "port": {
      "value": "0",
      "type": "number",
      "label": "Port",
      "editable": true,
      "secret": false,
      "required": false
    },
    "ssl": {
      "value": "true",
      "type": "boolean",
      "label": "Use SSL / TLS",
      "editable": true,
      "secret": false,
      "required": false
    },
    "pollIntervalSeconds": {
      "value": "60",
      "type": "number",
      "label": "Poll interval seconds",
      "editable": true,
      "secret": false,
      "required": true
    },
    "foldersInclude": {
      "value": "Inbox",
      "type": "list",
      "label": "Folders include",
      "description": "One folder per line",
      "editable": true,
      "secret": false,
      "required": false
    },
    "foldersExclude": {
      "value": "",
      "type": "list",
      "label": "Folders exclude",
      "description": "One folder per line. Example: Inbox/CI/CD",
      "editable": true,
      "secret": false,
      "required": false
    },
    "markNoiseAsRead": {
      "value": "true",
      "type": "boolean",
      "label": "Mark noise as read",
      "editable": true,
      "secret": false,
      "required": false
    },
    "moveProcessedMail": {
      "value": "false",
      "type": "boolean",
      "label": "Move processed mail",
      "editable": true,
      "secret": false,
      "required": false
    },
    "processedFolder": {
      "value": "processed",
      "type": "string",
      "label": "Processed folder",
      "editable": true,
      "secret": false,
      "required": false
    },
    "draftFolder": {
      "value": "drafts",
      "type": "string",
      "label": "Draft folder",
      "editable": true,
      "secret": false,
      "required": false
    }
  }
}
```

### Применить настройки MailAgent

```http
PUT /api/control/settings
```

Request:

```json
{
  "settings": {
    "enabled": "true",
    "protocol": "ews",
    "login": "user@example.com",
    "password": "plain-value-only-on-write",
    "serverUrl": "https://exchange.example.com/EWS/Exchange.asmx",
    "port": "443",
    "ssl": "true",
    "pollIntervalSeconds": "60",
    "foldersInclude": "Inbox",
    "foldersExclude": "Inbox/CI/CD\nJunk Email",
    "markNoiseAsRead": "true",
    "moveProcessedMail": "true"
  }
}
```

Response:

```json
{
  "pluginCode": "mail",
  "status": "APPLIED",
  "appliedAt": "2026-06-22T20:30:00",
  "applied": {
    "enabled": "true",
    "protocol": "ews",
    "pollIntervalSeconds": "60",
    "foldersInclude": "Inbox",
    "foldersExclude": "Inbox/CI/CD\nJunk Email"
  },
  "ignored": {
    "password": "secret value accepted but not returned"
  },
  "message": "MailAgent settings applied. Polling will restart with new config."
}
```

### Получить runtime status

```http
GET /api/control/status
```

Response:

```json
{
  "pluginCode": "mail",
  "status": "UP",
  "enabled": true,
  "polling": true,
  "protocol": "ews",
  "lastPollAt": "2026-06-22T20:30:00",
  "lastPollResult": "3 messages scanned",
  "configVersion": 3
}
```

### Получить audit применения настроек

```http
GET /api/control/audit
```

Response:

```json
[
  {
    "appliedAt": "2026-06-22T20:30:00",
    "status": "APPLIED",
    "changedKeys": ["enabled", "protocol", "foldersExclude"],
    "message": "Settings applied and polling restarted"
  }
]
```

## Runtime поведение

### Enabled

`enabled` не запускает и не останавливает JVM процесс.

```text
enabled=false -> MailAgent process alive, polling disabled
enabled=true  -> MailAgent process alive, polling enabled
```

### При применении настроек

MailAgent должен:

1. Валидировать входящую map.
2. Преобразовать строки в нужные типы.
3. Обновить runtime config.
4. Если изменились polling/mail параметры — безопасно перезапустить polling loop.
5. Не логировать plain password/token.
6. Записать audit/log о применении.
7. Вернуть `APPLIED` или `FAILED`.

### Пример логов

```text
INFO  MailControlController - Received settings update: keys=[enabled, protocol, pollIntervalSeconds, foldersExclude]
INFO  MailRuntimeConfigService - Applied mail setting: enabled=true
INFO  MailRuntimeConfigService - Applied mail setting: protocol=ews
INFO  MailRuntimeConfigService - Applied mail setting: pollIntervalSeconds=60
INFO  MailRuntimeConfigService - Applied mail setting: foldersExclude=[Inbox/CI/CD, Junk Email]
INFO  MailPollingService - Restarting polling loop due to config update version=3
```

Для секретов:

```text
INFO  MailRuntimeConfigService - Applied mail secret: password=<masked>
```

Запрещено:

```text
password=my-real-password
```

## Изменения в схеме БД MailAgent

Если в MailAgent уже есть БД/schema `mailagent`, добавить таблицу audit.

### `mailagent.control_settings_audit`

```sql
CREATE TABLE mailagent.control_settings_audit (
    id BIGSERIAL PRIMARY KEY,
    config_version BIGINT NOT NULL,
    changed_keys_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    applied_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL,
    message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

Важно: `request_json` и `applied_json` не должны содержать plain secret values.

## Изменения в конфигурации

Текущие настройки из `application.yml` остаются fallback.

При старте:

1. MailAgent поднимается с локальным config.
2. Если MemoryService доступен, MemoryService может позже применить настройки через `/api/control/settings`.
3. Если MemoryService недоступен, MailAgent продолжает работать по локальному config.

## Acceptance Criteria

1. В `JavaMailAgent` есть `GET /api/control/settings`.
2. Endpoint возвращает descriptor настроек в формате key-value map с metadata.
3. В descriptor есть минимум настройки: `enabled`, `protocol`, `login`, `password`, `serverUrl`, `host`, `port`, `ssl`, `pollIntervalSeconds`, `foldersInclude`, `foldersExclude`, `markNoiseAsRead`, `moveProcessedMail`, `processedFolder`, `draftFolder`.
4. В `JavaMailAgent` есть `PUT /api/control/settings`.
5. `PUT /api/control/settings` применяет измененные значения в runtime config.
6. `enabled=false` останавливает polling, но не JVM процесс.
7. `enabled=true` включает polling, если конфигурация валидна.
8. При изменении polling/mail настроек polling loop безопасно перезапускается.
9. Секреты принимаются только на запись и не возвращаются в открытом виде.
10. Plain password/token не попадает в application logs.
11. После применения настроек пишется лог со списком примененных ключей.
12. После применения настроек пишется audit-запись в БД или in-memory audit MVP.
13. Есть `GET /api/control/status`.
14. Есть `GET /api/control/audit`.
15. Добавлен E2E сценарий для MailAgent control API.

## Как тестировать

Создать сценарий:

```text
JavaMailAgent/test_e2e/08_control_settings.md
```

Проверки:

1. `GET /api/control/settings` возвращает `pluginCode=mail`.
2. Descriptor содержит `settings.enabled`.
3. Descriptor содержит `settings.protocol.options`.
4. `PUT /api/control/settings` с `enabled=false` возвращает `APPLIED`.
5. `GET /api/control/status` показывает `enabled=false`, `polling=false`.
6. `PUT /api/control/settings` с `enabled=true` возвращает `APPLIED`.
7. `GET /api/control/status` показывает `enabled=true`.
8. `PUT /api/control/settings` с password не возвращает password обратно.
9. `GET /api/control/audit` содержит запись о применении.
10. Логи не содержат plain password.

## Зависимости от MemoryService

MemoryService должен реализовать универсальный UI/proxy:

```text
CR-MEM-010-universal-plugin-control-ui.md
```

## Out of Scope

В этот CR не входит:

- реализация универсального UI в MemoryService;
- управление JVM процессом MailAgent;
- Docker/Kubernetes/systemd restart;
- полноценный secret vault;
- реализация ChatAgent;
- реализация RAG control API.

## Future

После реализации MailAgent control API аналогичный контракт нужно добавить в `JavaRagService`:

```text
CR-RAG-XXX-plugin-control-api.md
```

Для RAG настройки могут включать:

- enabled;
- scheduler enabled;
- scan interval seconds;
- rag inbox path;
- embedding model;
- opensearch URL;
- topK default;
- validation strict mode.
