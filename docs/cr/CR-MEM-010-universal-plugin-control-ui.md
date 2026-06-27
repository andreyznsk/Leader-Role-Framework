# CR-MEM-010: Universal Plugin Control UI

**Дата:** 2026-06-22  
**Статус:** Implemented  
**Сервис:** MEM  
**Зависимости:** CR-MEM-009, CR-MAIL-004, JavaMailAgent, JavaRagService

## Проблема / Мотивация

После появления централизованных настроек плагинов в MemoryService возникает следующий архитектурный вопрос: MemoryService не должен знать внутреннюю структуру настроек каждого плагина заранее.

Если для `MailAgent`, `RagService`, будущего `ChatAgent` и других сервисов делать отдельные формы руками, UI быстро станет жёстко связан с конкретными сервисами.

Нужно сделать универсальную модель:

1. Каждый подключенный сервис-плагин сам сообщает MemoryService список своих настроек.
2. Формат настроек — простая key-value map с метаданными.
3. MemoryService строит универсальный UI по этой map.
4. Пользователь меняет значения в UI.
5. MemoryService отправляет изменения обратно в соответствующий plugin через `/api/control`.
6. Plugin применяет настройки у себя и пишет в лог, что именно применилось.

Первичные плагины для MVP:

- `mail` — JavaMailAgent;
- `rag` — JavaRagService.

## Решение

Добавить в `JavaMemoryService` универсальный Plugin Control UI и backend-интеграцию с `/api/control` каждого подключенного плагина.

MemoryService должен работать как control plane:

```text
MemoryService /ui/settings
        ↓
GET plugin /api/control/settings
        ↓
универсальный UI по key-value map
        ↓
пользователь меняет значения
        ↓
POST/PUT plugin /api/control/settings
        ↓
plugin применяет config и пишет audit/log
```

## Основной принцип

MemoryService не должен иметь hardcoded HTML-форму отдельно для Mail, RAG, Chat.

Вместо этого каждый plugin возвращает descriptor:

```json
{
  "pluginCode": "mail",
  "pluginName": "Mail Agent",
  "version": 1,
  "settings": {
    "enabled": {
      "value": "true",
      "type": "boolean",
      "label": "Enabled",
      "description": "Enable or disable mail polling",
      "editable": true,
      "secret": false,
      "required": true
    },
    "protocol": {
      "value": "maildev",
      "type": "select",
      "label": "Protocol",
      "options": ["maildev", "imap", "ews"],
      "editable": true,
      "secret": false,
      "required": true
    }
  }
}
```

## Изменения в UI

### `/ui/settings`

Добавить универсальный блок Plugin Settings.

Структура:

```text
Settings
├── System
├── Plugins
│   ├── Mail Agent
│   │   └── dynamic settings form from /api/control/settings
│   └── RAG Service
│       └── dynamic settings form from /api/control/settings
└── Routing future
```

### Universal Plugin Settings Form

UI должен уметь рендерить поля по типу:

| type | UI элемент |
|------|------------|
| string | input text |
| number | input number |
| boolean | checkbox / toggle |
| select | select dropdown |
| text | textarea |
| list | textarea, one value per line |
| secret | password input + masked stored value (`*****`) |

Для каждого поля показывать:

- label;
- current value;
- description, если есть;
- validation hint, если есть;
- required marker;
- readonly state, если `editable=false`.

### Save flow

При сохранении конкретного plugin:

1. UI собирает измененные значения.
2. MemoryService отправляет их в plugin:

```http
PUT {plugin.baseUrl}/api/control/settings
```

3. Plugin возвращает applied result.
4. MemoryService показывает результат пользователю.
5. MemoryService обновляет локальный snapshot настроек.
6. UI может гидратировать форму актуальным descriptor JSON сразу после bootstrap API-запросов.

## Изменения в API MemoryService

### Получить список зарегистрированных control plugins

```http
GET /api/settings/control/plugins
```

Response:

```json
[
  {
    "code": "mail",
    "name": "Mail Agent",
    "baseUrl": "http://localhost:8080",
    "status": "UP",
    "lastSyncAt": "2026-06-22T20:00:00"
  },
  {
    "code": "rag",
    "name": "RAG Service",
    "baseUrl": "http://localhost:8081",
    "status": "UP",
    "lastSyncAt": "2026-06-22T20:00:00"
  }
]
```

### Получить descriptor настроек плагина через MemoryService proxy

```http
GET /api/settings/control/plugins/{code}/settings
```

MemoryService внутри вызывает:

```http
GET {plugin.baseUrl}/api/control/settings
```

### Применить настройки плагина через MemoryService proxy

```http
PUT /api/settings/control/plugins/{code}/settings
```

Request:

```json
{
  "settings": {
    "enabled": "true",
    "pollIntervalSeconds": "60",
    "foldersExclude": "Inbox/CI/CD\nJunk Email"
  }
}
```

MemoryService внутри вызывает:

```http
PUT {plugin.baseUrl}/api/control/settings
```

### Получить историю применения настроек

```http
GET /api/settings/control/plugins/{code}/audit
```

MVP может читать из локальной таблицы MemoryService или проксировать plugin audit endpoint.

### Browser debug / XHR visibility

UI сохраняет plugin settings через browser `fetch`, а initial bootstrap страницы может запрашивать:

- `/api/settings/system`
- `/api/settings/control/plugins`
- `/api/settings/control/plugins/{code}/settings`
- `/api/settings/control/plugins/{code}/audit`

Это позволяет видеть реальные запросы и JSON responses в `DevTools -> Fetch/XHR`.

## Изменения в схеме БД MemoryService

### Таблица `memory.control_plugins`

```sql
CREATE TABLE memory.control_plugins (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    last_sync_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

### Таблица `memory.control_plugin_settings_snapshot`

```sql
CREATE TABLE memory.control_plugin_settings_snapshot (
    id BIGSERIAL PRIMARY KEY,
    plugin_code VARCHAR(64) NOT NULL,
    descriptor_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    config_version BIGINT NOT NULL DEFAULT 1,
    synced_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(plugin_code)
);
```

### Таблица `memory.control_plugin_audit`

```sql
CREATE TABLE memory.control_plugin_audit (
    id BIGSERIAL PRIMARY KEY,
    plugin_code VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL,
    message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

## Дефолтные плагины MVP

При старте MemoryService через migration/seed или application config должны быть зарегистрированы:

```text
mail -> http://localhost:8080
rag  -> http://localhost:8081
```

URL должен быть настраиваемым через `application.yml`.

## Acceptance Criteria

1. В `JavaMemoryService` есть универсальный UI для настроек подключенных плагинов.
2. UI не содержит hardcoded формы только под MailAgent.
3. UI строится по descriptor/map, полученной от plugin `/api/control/settings`.
4. Поддержаны типы `string`, `number`, `boolean`, `select`, `text`, `list`, `secret`.
5. Есть API `GET /api/settings/control/plugins`.
6. Есть API `GET /api/settings/control/plugins/{code}/settings`.
7. Есть API `PUT /api/settings/control/plugins/{code}/settings`.
8. MemoryService умеет проксировать чтение настроек в `mail` plugin.
9. MemoryService умеет проксировать чтение настроек в `rag` plugin.
10. MemoryService умеет отправлять измененные настройки в нужный plugin.
11. После успешного применения MemoryService сохраняет snapshot descriptor/settings.
12. После успешного применения MemoryService пишет запись в audit.
13. В UI видно, что настройки применены или не применены.
14. Если plugin недоступен, UI показывает ошибку без падения MemoryService.
15. Добавлен E2E сценарий для `/ui/settings` и `/api/settings/control/plugins/**`.

## Как тестировать

Создать сценарий:

```text
JavaMemoryService/test_e2e/13_universal_plugin_control_ui.md
```

Проверки:

1. `GET /api/settings/control/plugins` возвращает `mail` и `rag`.
2. `GET /api/settings/control/plugins/mail/settings` возвращает descriptor.
3. `GET /api/settings/control/plugins/rag/settings` возвращает descriptor.
4. `PUT /api/settings/control/plugins/mail/settings` меняет тестовое значение.
5. `PUT /api/settings/control/plugins/rag/settings` меняет тестовое значение.
6. `/ui/settings` содержит универсальную форму для Mail.
7. `/ui/settings` содержит универсальную форму для RAG.
8. При недоступном plugin возвращается понятная ошибка.

## Зависимости от других сервисов

Этот CR зависит от появления `/api/control` в подключенных plugin-сервисах:

- `JavaMailAgent` — см. `CR-MAIL-004-plugin-control-api.md`;
- `JavaRagService` — см. `CR-RAG-001-plugin-control-api.md`.

## Out of Scope

В этот CR не входит:

- реализация control API внутри MailAgent;
- реализация control API внутри RagService;
- сложный secret vault;
- hot reload всех возможных runtime параметров;
- start/stop JVM процесса;
- Kubernetes deployment control;
- systemd/docker process management.

## Важное архитектурное решение

`enabled` в настройках плагина управляет активностью плагина внутри уже запущенного процесса, а не жизненным циклом JVM.

То есть:

```text
enabled=false -> plugin process alive, polling/indexing disabled
enabled=true  -> plugin process alive, polling/indexing enabled
```

Запуск/остановка Java процесса — отдельный будущий CR про runtime/process control.
