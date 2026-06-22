# CR-MEM-009: Plugin Settings Control Plane

**Дата:** 2026-06-22  
**Статус:** Draft  
**Сервис:** MEM  
**Зависимости:** JavaMailAgent, будущий ChatAgent, common

## Проблема / Мотивация

Сейчас настройки интеграций логически размазаны по отдельным сервисам. Например, настройки подключения к почте естественно хочется добавить в `JavaMailAgent`, но это создаёт неправильное направление архитектуры: каждый агент начинает иметь свой отдельный UI и свою отдельную модель конфигурации.

Целевая архитектура LeaderOS должна быть другой:

- `JavaMemoryService` — ядро системы и единая точка управления;
- внешние сервисы работают как подключаемые плагины / источники событий;
- `MailAgent`, будущий `ChatAgent`, `CalendarAgent` и другие агенты не должны владеть пользовательскими настройками;
- настройки должны задаваться в одном месте через UI MemoryService;
- плагины должны получать свою конфигурацию из MemoryService и отправлять туда raw-события для дальнейшей маршрутизации.

Это важно перед добавлением новых источников, особенно `ChatAgent`, который будет захватывать сообщения из чатов и маршрутизировать их аналогично почте.

## Решение

Добавить в `JavaMemoryService` централизованный модуль настроек плагинов.

Новый UI:

```text
/ui/settings
```

Новый раздел навигации:

```text
Settings
```

MemoryService становится `control plane` для всех подключаемых источников.

Плагины:

```text
Mail Plugin
Chat Plugin
Calendar Plugin future
Knowledge Plugin future
```

Каждый plugin имеет:

- уникальный код;
- тип;
- статус включения;
- настройки подключения;
- настройки include/exclude источников;
- настройки polling / scheduler;
- routing policy;
- последнее состояние health/status.

## Целевая модель

```text
JavaMemoryService
├── UI /ui/settings
├── REST API /api/settings/plugins/**
├── хранит настройки плагинов
├── хранит правила маршрутизации
├── показывает активный профиль приложения
├── показывает зарегистрированные плагины
└── отдаёт конфигурацию внешним агентам

JavaMailAgent
├── получает config из MemoryService
├── читает почту
└── отправляет raw email/capture/event в MemoryService

ChatAgent future
├── получает config из MemoryService
├── читает сообщения из чатов
└── отправляет raw chat/capture/event в MemoryService
```

## UI

### `/ui/settings`

Страница должна содержать блоки:

### 1. System Settings

Показывать read-only информацию:

- active Spring profiles;
- application name;
- Java version;
- MemoryService status;
- agent provider: `mock | ollama | claude | gigachat`;
- registered plugins;
- last configuration update time.

### 2. Plugin List

Таблица:

| Plugin | Type | Enabled | Status | Last heartbeat | Actions |
|--------|------|---------|--------|----------------|---------|
| Mail | MAIL | true | UP / DOWN / UNKNOWN | datetime | Edit |
| Chat | CHAT | false | NOT_CONFIGURED | — | Edit |

### 3. Mail Plugin Settings

Поля:

- enabled: boolean;
- protocol: `maildev | imap | ews`;
- login;
- password / secret value;
- server URL / host;
- port;
- use SSL/TLS;
- poll interval seconds;
- folders include;
- folders exclude;
- mark noise as read: boolean;
- move processed: boolean;
- processed folder;
- draft folder;
- connection test button.

Важно: пароль в UI не отображать в открытом виде. После сохранения показывать `********`.

### 4. Chat Plugin Settings future

Заложить структуру, но можно оставить disabled / placeholder.

Поля будущего плагина:

- enabled;
- provider: `telegram | slack | mattermost | custom`;
- server URL;
- token / secret;
- channels include;
- channels exclude;
- users include;
- users exclude;
- poll interval / webhook mode;
- routing policy.

### 5. Routing Settings

Общие правила маршрутизации событий:

| Input type | Route |
|-----------|-------|
| REQUEST | create pending task |
| DRAFT | create draft / save proposal |
| NOISE | ignore / archive |
| CAPTURE | create raw capture |
| KNOWLEDGE | send to RAG inbox |
| RISK | create risk |
| NOTE | create note |
| QUESTION | create question |

На первом этапе routing можно сделать read-only с дефолтными значениями.

## Изменения в API

Добавить REST API в JavaMemoryService.

### Получить все настройки плагинов

```http
GET /api/settings/plugins
```

Response:

```json
[
  {
    "code": "mail",
    "type": "MAIL",
    "enabled": true,
    "status": "UP",
    "lastHeartbeatAt": "2026-06-22T20:00:00",
    "updatedAt": "2026-06-22T19:30:00"
  }
]
```

### Получить настройки одного плагина

```http
GET /api/settings/plugins/{code}
```

Response example for mail:

```json
{
  "code": "mail",
  "type": "MAIL",
  "enabled": true,
  "config": {
    "protocol": "ews",
    "login": "user@example.com",
    "serverUrl": "https://exchange.example.com/EWS/Exchange.asmx",
    "port": 443,
    "ssl": true,
    "pollIntervalSeconds": 60,
    "foldersInclude": ["Inbox"],
    "foldersExclude": ["Inbox/CI/CD", "Junk Email"],
    "markNoiseAsRead": true,
    "moveProcessed": true,
    "processedFolder": "processed",
    "draftFolder": "drafts"
  }
}
```

Пароль/секрет не возвращать в открытом виде.

### Обновить настройки плагина

```http
PUT /api/settings/plugins/{code}
```

Request:

```json
{
  "enabled": true,
  "config": {
    "protocol": "ews",
    "login": "user@example.com",
    "password": "plain-value-only-on-write",
    "serverUrl": "https://exchange.example.com/EWS/Exchange.asmx",
    "port": 443,
    "ssl": true,
    "pollIntervalSeconds": 60,
    "foldersInclude": ["Inbox"],
    "foldersExclude": ["Inbox/CI/CD", "Junk Email"]
  }
}
```

### Получить system settings

```http
GET /api/settings/system
```

Response:

```json
{
  "application": "JavaMemoryService",
  "activeProfiles": ["local"],
  "agentProvider": "mock",
  "javaVersion": "21",
  "status": "UP"
}
```

### Plugin heartbeat

Плагин отправляет heartbeat в MemoryService.

```http
POST /api/settings/plugins/{code}/heartbeat
```

Request:

```json
{
  "status": "UP",
  "message": "Mail polling is active",
  "details": {
    "lastPollAt": "2026-06-22T20:00:00",
    "lastPollResult": "3 messages scanned"
  }
}
```

### Plugin config endpoint для внешних агентов

```http
GET /api/plugins/{code}/config
```

Используется `JavaMailAgent` и будущими агентами при старте.

Response:

```json
{
  "code": "mail",
  "enabled": true,
  "configVersion": 3,
  "config": {
    "protocol": "ews",
    "login": "user@example.com",
    "serverUrl": "https://exchange.example.com/EWS/Exchange.asmx",
    "port": 443,
    "ssl": true,
    "pollIntervalSeconds": 60,
    "foldersInclude": ["Inbox"],
    "foldersExclude": ["Inbox/CI/CD"]
  }
}
```

## Изменения в схеме БД

Схема: `memory`

### Таблица `memory.plugin_settings`

```sql
CREATE TABLE memory.plugin_settings (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    secret_ref VARCHAR(255),
    config_version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

### Таблица `memory.plugin_status`

```sql
CREATE TABLE memory.plugin_status (
    id BIGSERIAL PRIMARY KEY,
    plugin_code VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    message TEXT,
    details_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_heartbeat_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

### Таблица `memory.routing_settings`

```sql
CREATE TABLE memory.routing_settings (
    id BIGSERIAL PRIMARY KEY,
    input_type VARCHAR(64) NOT NULL UNIQUE,
    route VARCHAR(128) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    config_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
```

## Безопасность секретов

MVP-вариант:

- пароль принимается только на запись;
- в API и UI возвращается только masked value;
- хранение можно временно делать в `config_json`, но это технический долг.

Целевой вариант:

- секреты вынести в отдельный механизм;
- в `plugin_settings.secret_ref` хранить ссылку на секрет;
- не логировать password/token;
- не возвращать password/token из REST API;
- добавить отдельный CR на secret storage.

## Изменения в JavaMailAgent

На первом этапе не переносить всю логику сразу.

Минимальный будущий контракт:

1. При старте `JavaMailAgent` вызывает:

```http
GET http://localhost:8082/api/plugins/mail/config
```

2. Если MemoryService недоступен, MailAgent использует локальный fallback из `application.yml`.

3. После каждого poll-cycle отправляет heartbeat:

```http
POST http://localhost:8082/api/settings/plugins/mail/heartbeat
```

4. Настройки `mail.folders.exclude` постепенно мигрируются из `application.yml` в MemoryService.

## Изменения в навигации UI

Добавить пункт:

```text
Settings
```

Рядом с существующими страницами:

- Today;
- Notes;
- Captures;
- Knowledge;
- Stats;
- Settings.

## Acceptance Criteria

1. В `JavaMemoryService` появилась страница `/ui/settings`.
2. На странице отображается текущий active profile.
3. На странице отображается agent provider.
4. На странице отображается список plugins.
5. Можно открыть настройки `mail` plugin.
6. Можно сохранить настройки `mail` plugin.
7. Пароль не отображается в открытом виде после сохранения.
8. Есть API `GET /api/settings/system`.
9. Есть API `GET /api/settings/plugins`.
10. Есть API `GET /api/settings/plugins/mail`.
11. Есть API `PUT /api/settings/plugins/mail`.
12. Есть API `GET /api/plugins/mail/config` для MailAgent.
13. Есть API heartbeat для plugin status.
14. Добавлены Flyway migrations для новых таблиц.
15. Добавлены базовые E2E сценарии.

## Как тестировать

### Unit / integration

- проверить CRUD `plugin_settings`;
- проверить masking password/token;
- проверить получение system settings;
- проверить сохранение include/exclude folders;
- проверить heartbeat update.

### E2E сценарии

Создать файл:

```text
JavaMemoryService/test_e2e/12_settings_plugins.md
```

Сценарии:

1. `GET /api/settings/system` возвращает active profile.
2. `GET /api/settings/plugins` возвращает список plugins.
3. `PUT /api/settings/plugins/mail` сохраняет mail config.
4. `GET /api/settings/plugins/mail` возвращает config без plain password.
5. `GET /api/plugins/mail/config` возвращает enabled config для MailAgent.
6. `POST /api/settings/plugins/mail/heartbeat` обновляет plugin status.
7. `/ui/settings` открывается и содержит `Settings`, `Mail Plugin`, `Active profile`.

## Риски

1. Хранение пароля в БД может быть небезопасным.
   - Решение: для MVP masked output, далее отдельный CR на secret storage.

2. MailAgent может стартовать раньше MemoryService.
   - Решение: fallback на локальный config + retry получения remote config.

3. Слишком рано усложнить plugin architecture.
   - Решение: сделать простой JSONB-based config без абстрактного plugin framework на первом этапе.

4. UI настроек может стать свалкой.
   - Решение: разделить System / Plugins / Routing.

## Out of Scope

В этот CR не входит:

- полноценная реализация ChatAgent;
- secret vault;
- multi-user / multi-tenant settings;
- динамическое hot reload конфигурации без рестарта агента;
- сложный rule engine для routing;
- прямое управление RAG настройками.

## Следующие CR

1. `CR-MAIL-004-remote-config-from-memory-service.md`  
   Перевести `JavaMailAgent` на получение настроек из MemoryService.

2. `CR-MEM-010-secret-storage.md`  
   Безопасное хранение секретов для plugin settings.

3. `CR-CHAT-001-chat-agent-plugin.md`  
   Новый ChatAgent как источник сообщений.

4. `CR-MEM-011-routing-policy-ui.md`  
   Настройка правил маршрутизации событий из UI.
