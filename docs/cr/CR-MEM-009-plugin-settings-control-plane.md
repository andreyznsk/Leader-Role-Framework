# CR-MEM-009: Plugin Settings Control Plane

**Дата:** 2026-06-22  
**Статус:** Implemented  
**Сервис:** MEM  
**Зависимости:** JavaMailAgent, JavaRagService, common

## Проблема / Мотивация

Настройки подключенных сервисов не должны быть размазаны по отдельным UI и отдельным моделям конфигурации внутри каждого агента.

Целевая архитектура LeaderOS:

- `JavaMemoryService` является единой точкой управления;
- внешние сервисы работают как подключаемые runtime plugins;
- пользователь настраивает систему через один UI `/ui/settings`;
- сами plugins отдают descriptor настроек и принимают runtime-изменения через control API;
- статус plugin-сервисов определяется по их health endpoint, а не по прямому доступу к их внутренней БД.

## Решение

В `JavaMemoryService` реализован централизованный control plane для plugin settings.

Основной flow:

```text
Browser
  ↓
GET /ui/settings
  ↓
JavaMemoryService
  ├─ GET /api/settings/system
  ├─ GET /api/settings/control/plugins
  ├─ GET /api/settings/control/plugins/{code}/settings
  └─ GET /api/settings/control/plugins/{code}/audit
        ↓
      plugin /api/control/**
```

При сохранении:

```text
Browser
  ↓
PUT /api/settings/control/plugins/{code}/settings
  ↓
JavaMemoryService
  ↓
PUT {plugin.baseUrl}/api/control/settings
  ↓
plugin runtime config updated
```

## Текущая архитектурная модель

```text
JavaMemoryService
├── UI /ui/settings
├── REST API /api/settings/system
├── REST API /api/settings/control/plugins/**
├── хранит registry control plugins
├── хранит snapshot descriptor
├── хранит audit proxy/history
└── использует /actuator/health для live plugin status

JavaMailAgent
├── /api/control/settings
├── /api/control/status
├── /api/control/audit
└── /actuator/health

JavaRagService
├── /api/control/settings
├── /api/control/status
├── /api/control/audit
└── /actuator/health
```

## UI

### `/ui/settings`

Страница содержит:

- `System Settings`;
- `Routing Settings`;
- `Registered Control Plugins`;
- отдельные collapsible sections по каждому plugin;
- universal dynamic form, построенную по descriptor `settings`;
- audit block по каждому plugin.
- prompt fields plugins, если plugin публикует их через descriptor.

### Список control plugins

Показываются:

- `code`;
- `name`;
- `baseUrl`;
- `status`;
- `lastSyncAt`;
- action `Open`.

`status` определяется live healthcheck на:

```text
{plugin.baseUrl}/actuator/health
```

### Universal plugin settings form

Поддерживаемые типы:

| type | UI элемент |
|------|------------|
| string | input text |
| number | input number |
| boolean | checkbox / toggle |
| select | select dropdown |
| text | textarea |
| list | textarea, one value per line |
| secret | password input + masked stored value |

Для каждого поля UI показывает:

- `label`;
- текущее значение;
- `description`, если есть;
- `required`;
- `editable/read-only`.

Для `secret`:

- plain secret никогда не возвращается;
- при сохраненном секрете показывается маска `*****`;
- при отсутствии секрета показывается placeholder `Enter new secret`.

Для prompt-полей:

- используются `text` / textarea controls;
- UI не знает семантику prompt-а и не хранит prompt как source of truth;
- длинные prompt templates редактируются и сохраняются как обычные runtime settings;
- после сохранения source of truth остаётся в БД самого plugin-сервиса.

### Browser debug behavior

UI-операции по API выполняются через browser `fetch`.

Это позволяет:

- видеть запросы в `DevTools -> Fetch/XHR`;
- видеть request/response JSON в browser console;
- использовать `?ui_debug=1`, чтобы временно отключать auto-navigation после успешных действий.

## Изменения в API

### System settings

```http
GET /api/settings/system
```

Возвращает:

- application;
- activeProfiles;
- agentProvider;
- javaVersion;
- status;
- lastConfigurationUpdateAt;
- registeredPlugins;
- routingDefaults.

### Registered control plugins

```http
GET /api/settings/control/plugins
```

Возвращает список control plugins:

```json
[
  {
    "code": "mail",
    "name": "Mail Agent",
    "baseUrl": "http://localhost:8080",
    "enabled": true,
    "status": "UP",
    "lastSyncAt": "2026-06-22T20:00:00"
  },
  {
    "code": "rag",
    "name": "RAG Service",
    "baseUrl": "http://localhost:8081",
    "enabled": true,
    "status": "UP",
    "lastSyncAt": "2026-06-22T20:00:00"
  }
]
```

### Plugin descriptor proxy

```http
GET /api/settings/control/plugins/{code}/settings
```

Внутри MemoryService вызывает:

```http
GET {plugin.baseUrl}/api/control/settings
```

### Plugin update proxy

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

Prompt templates подчиняются тому же контракту. Если plugin публикует поле вроде
`classificationPrompt`, MemoryService проксирует его как обычное `settings[classificationPrompt]`.

Внутри MemoryService вызывает:

```http
PUT {plugin.baseUrl}/api/control/settings
```

### Plugin audit proxy

```http
GET /api/settings/control/plugins/{code}/audit
```

MVP-реализация может:

- читать remote audit;
- либо показывать локально сохраненный snapshot/history при недоступном plugin.

## Legacy API

В `JavaMemoryService` остается legacy settings API:

- `GET /api/settings/plugins`
- `GET /api/settings/plugins/{code}`
- `PUT /api/settings/plugins/{code}`
- `POST /api/settings/plugins/{code}/heartbeat`
- `GET /api/plugins/{code}/config`

Но текущая архитектура UI/plugin control больше не строится вокруг этих endpoints. Основной control plane для runtime plugins идет через:

```text
/api/settings/control/plugins/**
```

## Изменения в схеме БД

Схема `memory` включает control plane таблицы:

- `control_plugins`
- `control_plugin_settings_snapshot`
- `control_plugin_audit`

Важно: snapshot в схеме `memory` не является master-хранилищем prompt-ов. Он нужен
для UI/offline отображения последнего известного descriptor-а. Каноническое хранение
prompt templates остаётся в БД самого plugin-сервиса.

Назначение:

- registry подключенных control plugins;
- последний synced descriptor snapshot;
- audit/history control операций.

## Безопасность секретов

- plain password/token не должны попадать в UI;
- plain password/token не должны попадать в logs;
- descriptor и audit используют masked representation;
- vault/secret storage остаются отдельным будущим шагом.

## Acceptance Criteria

1. В `JavaMemoryService` есть страница `/ui/settings`.
2. Есть API `GET /api/settings/system`.
3. Есть API `GET /api/settings/control/plugins`.
4. Есть API `GET /api/settings/control/plugins/{code}/settings`.
5. Есть API `PUT /api/settings/control/plugins/{code}/settings`.
6. Есть API `GET /api/settings/control/plugins/{code}/audit`.
7. UI строится по descriptor от plugin control API.
8. UI поддерживает типы `string`, `number`, `boolean`, `select`, `text`, `list`, `secret`.
9. Статус plugin определяется по `{baseUrl}/actuator/health`.
10. Недоступный plugin не ломает страницу `/ui/settings`.
11. Secret values не отображаются в открытом виде.
12. В DevTools видны browser fetch/XHR запросы к API.

## Как тестировать

### API

1. `GET /api/settings/system`
2. `GET /api/settings/control/plugins`
3. `GET /api/settings/control/plugins/mail/settings`
4. `GET /api/settings/control/plugins/rag/settings`
5. `PUT /api/settings/control/plugins/mail/settings`
6. `PUT /api/settings/control/plugins/rag/settings`
7. `GET /api/settings/control/plugins/mail/audit`

### UI

1. Открыть `/ui/settings`
2. Проверить system/routing/plugin sections
3. Проверить collapsible plugin settings
4. Проверить, что password отображается как `*****`
5. Проверить DevTools `Fetch/XHR`
6. Проверить `?ui_debug=1`

## Связанные CR

- `CR-MEM-010-universal-plugin-control-ui.md`
- `CR-MAIL-004-plugin-control-api.md`
- `CR-RAG-001-plugin-control-api.md`

## Out of Scope

- полноценный secret vault;
- multi-user / multi-tenant settings;
- управление JVM lifecycle plugin-процессов;
- Kubernetes/systemd/docker process management;
- ChatAgent runtime control API.
