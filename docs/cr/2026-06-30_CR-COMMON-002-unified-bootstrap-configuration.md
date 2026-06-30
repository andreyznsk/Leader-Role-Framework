# 2026-06-30_CR-COMMON-002: Unified Bootstrap Configuration

**Дата:** 2026-06-30  
**Статус:** Draft  
**Сервис:** COMMON  
**Тип:** Architecture / Cross-service  
**Приоритет:** HIGH  
**Зависимости:** JavaMailAgent, JavaMemoryService, JavaRagService, Plugin Control Plane

---

## Проблема / Мотивация

Сейчас bootstrap-настройки LeaderOS распределены между несколькими сервисами и разными файлами `application.yml` / `application-*.yml` / `application-*.properties`.

Это создаёт проблемы:

- один и тот же адрес или credential может быть продублирован в нескольких местах;
- пользователю сложно понять, какой файл нужно править для запуска системы;
- добавление нового модуля требует копирования конфигурационного подхода;
- часть настроек смешивается с Plugin Control Plane, хотя Control Plane должен управлять поведением уже запущенных сервисов, а не условиями их запуска;
- усложняется будущая упаковка продукта для постоянного тестирования простым пользователем.

В проекте уже есть модуль `common`, который является общей библиотекой для сервисов и содержит общую инфраструктуру, включая `AgentClient`. Поэтому единый механизм загрузки bootstrap-конфигурации должен быть реализован именно в `common`.

---

## Цель

Ввести единый файл bootstrap-конфигурации:

```text
leaderos.properties
```

Этот файл должен стать единственным source of truth для настроек, необходимых для запуска всех текущих и будущих модулей LeaderOS.

---

## Разделение ответственности

### `leaderos.properties`

Отвечает на вопрос:

```text
Как сервис запускается и куда подключается?
```

В файле должны храниться:

- активный профиль LeaderOS;
- server ports;
- URL-ы сервисов;
- PostgreSQL host/port/database/schema/user/password;
- OpenSearch URL;
- Ollama / GigaChat / Claude / other AI runtime bootstrap settings;
- mail protocol;
- mail server / EWS endpoint / IMAP endpoint;
- mail username/password/auth type/domain;
- workspace paths;
- пути к `capture-inbox`, `rag-inbox`, `drafts`, `processed`;
- прочие настройки, без которых сервис не может корректно стартовать.

### Plugin Control Plane

Отвечает на вопрос:

```text
Как уже запущенный сервис ведёт себя в runtime?
```

В Control Plane остаются:

- plugin enabled / disabled;
- scheduler enabled / disabled;
- runtime polling interval;
- prompt templates;
- prompt versions;
- RAG `topK` как runtime-настройка;
- validation enabled;
- retry policy;
- debug/runtime flags;
- plugin status;
- audit/history;
- test connection actions.

---

## Решение

### 1. Добавить общий bootstrap loader в `common`

Создать пакет:

```text
common/src/main/java/.../config
```

Предлагаемые классы:

```text
LeaderOsConfiguration
LeaderOsConfigurationProperties
LeaderOsPropertiesLoader
LeaderOsConfigLocationResolver
LeaderOsEnvironmentPostProcessor
LeaderOsBootstrapAutoConfiguration
```

Назначение:

- найти `leaderos.properties`;
- загрузить его до инициализации service-specific configuration;
- добавить значения в Spring Environment;
- дать сервисам единый typed access к bootstrap-настройкам;
- обеспечить одинаковый механизм для JavaMailAgent, JavaMemoryService, JavaRagService и будущих модулей.

---

### 2. Правила поиска файла

Приоритет источников:

```text
1. JVM system property:
   -Dleaderos.config=/path/to/leaderos.properties

2. Environment variable:
   LEADEROS_CONFIG=/path/to/leaderos.properties

3. Рабочая директория:
   ./leaderos.properties

4. Classpath fallback:
   classpath:leaderos.properties
```

Если файл не найден, поведение должно быть совместимым с текущими `application.yml` на переходный период, но в логах должно быть явное предупреждение.

---

### 3. Пример файла

Добавить в корень репозитория:

```text
leaderos.properties.example
```

Пример структуры:

```properties
####################################
# LeaderOS profile
####################################
leaderos.profile=local

####################################
# Service ports and URLs
####################################
memory.server.port=8082
mail.server.port=8080
rag.server.port=8081

memory.url=http://localhost:8082
mail.url=http://localhost:8080
rag.url=http://localhost:8081

####################################
# PostgreSQL
####################################
postgres.host=localhost
postgres.port=5432
postgres.database=leader_framework

postgres.memory.schema=memory
postgres.memory.username=memory_user
postgres.memory.password=memory_password

postgres.mail.schema=mailagent
postgres.mail.username=mailagent_user
postgres.mail.password=mailagent_password

postgres.rag.schema=rag
postgres.rag.username=rag_user
postgres.rag.password=rag_password

####################################
# OpenSearch
####################################
opensearch.url=http://localhost:9200

####################################
# AI Runtime
####################################
agent.provider=claude
ollama.url=http://localhost:11434

####################################
# Mail bootstrap
####################################
mail.protocol=ews
mail.auth.type=NTLM
mail.username=user@domain.ru
mail.password=change-me
mail.ews.url=https://outlook.domain.ru/EWS/Exchange.asmx
mail.ews.domain=
mail.folders.exclude=Inbox/CI/CD

####################################
# Workspace paths
####################################
workspace.path=./workspace
capture.inbox.path=./capture-inbox
drafts.path=./drafts
processed.path=./processed
rag.inbox.path=./JavaRagService/rag-inbox
```

---

### 4. Миграция сервисов

Все сервисы должны читать bootstrap-настройки из `common`.

#### JavaMemoryService

Перенести в `leaderos.properties`:

- `server.port` / `memory.server.port`;
- datasource URL/user/password;
- URLs плагинов `mail`, `rag`;
- workspace paths;
- agent provider bootstrap.

Оставить в `application.yml` только Spring Boot technical defaults:

- logging;
- actuator;
- thymeleaf;
- mvc/static resources;
- management endpoints.

#### JavaMailAgent

Перенести в `leaderos.properties`:

- `server.port` / `mail.server.port`;
- datasource URL/user/password;
- MemoryService URL;
- mail protocol;
- EWS/IMAP/Maildev endpoint;
- username/password/auth type/domain;
- folder exclude list;
- agent provider bootstrap;
- filesystem paths.

#### JavaRagService

Перенести в `leaderos.properties`:

- `server.port` / `rag.server.port`;
- datasource URL/user/password;
- OpenSearch URL;
- Ollama URL;
- RAG inbox path;
- agent provider bootstrap, если используется.

---

### 5. Изменить Plugin Control Plane descriptors

Bootstrap-настройки больше не должны быть редактируемыми через UI.

Запретить отображение или сделать read-only diagnostic-only для:

```text
mail.username
mail.password
mail.protocol
mail.ews.url
mail.auth.type
postgres.*
opensearch.url
ollama.url
agent.provider
server.port
memory.url
mail.url
rag.url
workspace.path
capture.inbox.path
rag.inbox.path
```

Runtime-настройки остаются в Control Plane.

---

### 6. Обратная совместимость

На переходный период:

- если `leaderos.properties` найден — он имеет приоритет;
- если не найден — сервисы стартуют со старым `application.yml` поведением;
- в логах выводится warning:

```text
leaderos.properties not found. Falling back to application.yml bootstrap configuration. This mode is deprecated.
```

---

## Изменения в API

Новых публичных REST API не требуется.

Допускается добавить internal diagnostic endpoint в MemoryService:

```http
GET /api/settings/bootstrap/status
```

Назначение: показать, откуда загружен `leaderos.properties`, без вывода secret values.

MVP может быть реализован без этого endpoint.

---

## Изменения в схеме БД

Новых таблиц не требуется.

Важно: bootstrap-настройки не переносить в БД. Они должны оставаться в файле `leaderos.properties`.

---

## Зависимости

- `common` должен быть собран и подключен ко всем сервисам;
- сервисы должны использовать общий механизм загрузки до инициализации datasource;
- Plugin Control Plane должен отличать bootstrap settings от runtime settings;
- документация должна быть обновлена.

---

## Acceptance Criteria

1. В корне проекта добавлен `leaderos.properties.example`.
2. В `common` реализован общий loader для `leaderos.properties`.
3. JavaMemoryService читает bootstrap-настройки через общий механизм.
4. JavaMailAgent читает bootstrap-настройки через общий механизм.
5. JavaRagService читает bootstrap-настройки через общий механизм.
6. Приоритет загрузки соблюдается:
   - `-Dleaderos.config`
   - `LEADEROS_CONFIG`
   - `./leaderos.properties`
   - classpath fallback
7. При отсутствии файла сохраняется временный fallback на текущий `application.yml`.
8. Bootstrap-настройки не редактируются через Control Plane UI.
9. Runtime-настройки Control Plane продолжают работать без регрессий.
10. Обновлены `README.md`, `ARCHITECTURE.md` и relevant RFC/common docs.
11. Обновлены test-runner scripts, если они завязаны на старые profile/application files.
12. Добавлены или обновлены E2E/smoke проверки запуска с `leaderos.properties`.

---

## Как тестировать

### Unit / component tests

- Проверить `LeaderOsConfigLocationResolver` для всех источников:
  - system property;
  - env var;
  - working directory;
  - classpath fallback;
  - no file fallback.

- Проверить masking secret values при логировании.

### Local smoke

```bash
cp leaderos.properties.example leaderos.properties
./test-runner/build.sh
./test-runner/start-services.sh --profile local
./test-runner/healthcheck.sh
```

Ожидаемо:

- MemoryService стартует на `memory.server.port`;
- MailAgent стартует на `mail.server.port`;
- RagService стартует на `rag.server.port`;
- datasource подключается из `leaderos.properties`;
- URLs сервисов берутся из `leaderos.properties`.

### Explicit config location

```bash
java -Dleaderos.config=/tmp/leaderos.properties -jar JavaMemoryService/target/memory-service.jar
```

Ожидаемо:

- сервис использует `/tmp/leaderos.properties`;
- в логах видно выбранный config location;
- secret values не печатаются.

### Control Plane regression

Проверить:

```bash
curl http://localhost:8082/api/settings/control/plugins
curl http://localhost:8082/api/settings/control/plugins/mail/settings
curl http://localhost:8082/api/settings/control/plugins/rag/settings
```

Ожидаемо:

- plugin status доступен;
- prompts доступны для редактирования;
- plugin enabled/scheduler enabled доступны;
- bootstrap secrets и endpoints не редактируются из UI.

---

## Риски

| Риск | Митигирующее действие |
|---|---|
| Поломка старого local запуска | Ввести fallback на старые `application.yml` |
| Секреты могут попасть в логи | Маскировать `password`, `secret`, `token`, `client-secret` |
| Control Plane потеряет часть настроек | Явно разделить bootstrap/runtime settings в descriptor layer |
| Сервисы стартуют до загрузки общего файла | Использовать EnvironmentPostProcessor / раннюю инициализацию |
| Разные naming conventions | Зафиксировать canonical property names в `leaderos.properties.example` |

---

## Документация

Обновить:

- `ARCHITECTURE.md` — раздел common и конфигурация;
- `README.md` — установка и запуск через `leaderos.properties`;
- `common/RFC/RFC-common.md` — новый bootstrap config module;
- документацию Plugin Control Plane — границы bootstrap/runtime;
- `test-runner` docs, если меняются параметры запуска.

---

## Definition of Done

- CR реализован;
- все сервисы стартуют локально с одним `leaderos.properties`;
- старые profile-specific файлы либо удалены, либо помечены deprecated;
- Control Plane не содержит редактируемых bootstrap-настроек;
- тесты и smoke checks проходят;
- документация обновлена;
- после проверки пользователя CR переводится в `DONE`, затем после merge — в `Implemented`.
