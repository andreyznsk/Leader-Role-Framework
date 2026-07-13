# 2026-07-13_CR-MEM-035: Создание Jira issue из задачи Today

**Дата:** 2026-07-13  
**Статус:** Implemented  
**Сервис:** MEM / COMMON  
**Тип:** Feature  
**GitHub Issue:** будет добавлен после создания Issue

## Проблема / Мотивация

LeaderOS хранит оперативные задачи техлида в JavaMemoryService и показывает их на странице `/ui/today`, но для передачи части работы в командный процесс пользователю приходится вручную создавать соответствующий тикет в Jira и повторно переносить заголовок, описание, исполнителя и проект.

Нужен управляемый MVP-flow: пользователь открывает задачу Today, нажимает «Создать задачу в Jira», проверяет предзаполненные поля в модальном окне и создаёт Jira issue. LeaderOS сохраняет устойчивую связь между локальной задачей и созданным Jira issue и не допускает повторного создания дубля.

## Принятые архитектурные решения

1. Отдельный Spring Boot сервис или отдельный Maven-модуль `JavaJiraConnector` не создаётся.
2. Техническая Jira-интеграция реализуется отдельной доменной областью `jira` в существующем модуле `common`.
3. JavaMemoryService остаётся владельцем:
   - Today UI и модального окна;
   - бизнес-flow создания Jira issue из LeaderOS task;
   - настроек Jira для MVP;
   - связи LeaderOS task ↔ Jira issue;
   - защиты от дублей.
4. Все настройки Jira для MVP хранятся только в `JavaMemoryService/src/main/resources/application*.yml` или `.properties` и могут переопределяться environment variables.
5. Runtime settings, Plugin Control Plane и отдельное хранение Jira-секретов в БД в рамках MVP не реализуются.
6. `common` является библиотекой и самостоятельно не стартует. Проверку Jira при запуске инициирует JavaMemoryService через компонент из `common`.
7. Ошибка Jira не должна останавливать JavaMemoryService. Интеграция переходит в состояние `UNAVAILABLE`, а UI блокирует создание тикета и показывает причину.

## Целевая схема

```text
Today UI
   ↓
JavaMemoryService
   ├── JiraTaskService
   ├── JiraStartupHealthChecker
   └── memory.task_external_issues
          ↓
common::jira::JiraClient
          ↓
Atlassian Jira REST API
```

## Scope MVP

### Входит

- доменная область `common/.../jira`;
- конфигурация Jira в JavaMemoryService application properties;
- проверка Jira при старте JavaMemoryService;
- allowlist Jira-проектов;
- кнопка «Создать задачу в Jira» в карточке задачи на `/ui/today`;
- аналогичное действие на `/ui/tasks/{id}/edit`, если это не усложняет переиспользование UI-компонента;
- модальное окно создания Jira issue;
- предзаполнение summary и description из LeaderOS task;
- выбор только из разрешённых проектов;
- выбор issue type;
- выбор исполнителя: текущий Jira-пользователь либо без назначения;
- создание Jira issue;
- сохранение Jira issue id, key и URL;
- защита от повторного создания;
- отображение ссылки на уже созданный Jira issue;
- обработка и отображение ошибок Jira;
- E2E-сценарий для нового flow.

### Не входит

- отдельный Jira plugin-service;
- Plugin Control Plane для Jira;
- хранение токена в PostgreSQL;
- двусторонняя синхронизация статусов;
- импорт Jira issues в LeaderOS;
- синхронизация комментариев и вложений;
- автоматическое создание без подтверждения пользователя;
- AI-генерация описания;
- Epic/Sub-task/parent hierarchy;
- редактирование Jira issue из LeaderOS.

## Настройки JavaMemoryService

Добавить типизированные properties, например:

```yaml
jira:
  enabled: false
  base-url: ${JIRA_BASE_URL:}
  username: ${JIRA_USERNAME:}
  token: ${JIRA_TOKEN:}
  auth-type: ${JIRA_AUTH_TYPE:BEARER}
  default-project: ${JIRA_DEFAULT_PROJECT:}
  allowed-projects: ${JIRA_ALLOWED_PROJECTS:}
  default-issue-type: ${JIRA_DEFAULT_ISSUE_TYPE:Task}
  startup-check-enabled: true
  timeout:
    connect-seconds: 5
    read-seconds: 20
```

Точные имена properties могут быть уточнены при реализации, но должны сохранять единый префикс `jira` и поддерживать environment override.

### Правила конфигурации

- при `jira.enabled=false` интеграция имеет статус `DISABLED`;
- `default-project` обязан входить в `allowed-projects`;
- UI никогда не должен показывать проекты вне allowlist;
- токен для MVP может храниться в локальном application YAML/properties, но пример конфигурации в репозитории должен использовать `${JIRA_TOKEN:}` и не содержать реальный секрет;
- секреты и реальные корпоративные адреса Jira не коммитить.

## Изменения в `common`

Создать отдельный пакет Jira, не связывая его с сущностями JavaMemoryService.

Предполагаемая структура:

```text
common/src/main/java/.../jira/
├── JiraClient.java
├── AtlassianJiraClient.java
├── JiraClientConfig.java
├── JiraConnectionChecker.java
├── JiraIntegrationProperties.java
├── dto/
│   ├── JiraCreateIssueRequest.java
│   ├── JiraCreateIssueResult.java
│   ├── JiraCurrentUser.java
│   ├── JiraProject.java
│   ├── JiraIssueType.java
│   └── JiraAssignableUser.java
└── exception/
    ├── JiraClientException.java
    ├── JiraAuthenticationException.java
    ├── JiraPermissionException.java
    └── JiraUnavailableException.java
```

Допустима адаптация имён под текущие package conventions проекта.

### Контракт JiraClient

Минимальный контракт должен позволять:

```java
JiraConnectionResult testConnection();
JiraCurrentUser getCurrentUser();
List<JiraProject> getProjects(Set<String> allowedProjectKeys);
List<JiraIssueType> getIssueTypes(String projectKey);
List<JiraAssignableUser> getAssignableUsers(String projectKey, String query);
JiraCreateIssueResult createIssue(JiraCreateIssueRequest request);
```

`common` не должен знать о `TaskEntity`, Today UI, таблице связи и правилах защиты от дублей.

## Проверка Jira при старте

JavaMemoryService создаёт startup checker после инициализации приложения.

Алгоритм:

1. Если `jira.enabled=false`, установить `DISABLED` и не выполнять HTTP-вызовы.
2. Проверить обязательную конфигурацию.
3. Проверить доступность Jira и аутентификацию.
4. Получить текущего Jira-пользователя.
5. Проверить каждый проект из allowlist.
6. Проверить доступные issue types для default project.
7. Сохранить in-memory snapshot статуса интеграции.

Минимальные состояния:

```text
DISABLED
AVAILABLE
UNAVAILABLE
```

Статус должен содержать диагностическое сообщение, но не должен раскрывать token.

При ошибке startup check:

- JavaMemoryService продолжает запуск;
- ошибка записывается в лог без секрета;
- кнопка создания Jira issue становится disabled;
- UI показывает короткую понятную причину.

## Изменения в схеме БД

Добавить новую Flyway migration в JavaMemoryService. Существующие migration-файлы не изменять.

Таблица:

```text
memory.task_external_issues
```

Минимальные поля:

| Поле | Назначение |
|---|---|
| `id` | PK |
| `task_id` | FK на локальную задачу |
| `external_system` | `JIRA` |
| `external_id` | внутренний Jira issue ID |
| `external_key` | человекочитаемый ключ, например `TEAM-123` |
| `external_url` | browser URL Jira issue |
| `project_key` | Jira project key |
| `status` | `CREATING`, `CREATED`, `FAILED` |
| `error_message` | последняя безопасная ошибка |
| `created_at` | дата создания связи |
| `updated_at` | дата изменения |

Ограничения:

```sql
UNIQUE (task_id, external_system)
```

Для `task_id` использовать FK и согласовать delete policy с существующей моделью задач. Предпочтительно не удалять аудит внешней связи случайно; если в проекте задачи архивируются, FK может оставаться без cascade.

## Защита от дублей и конкурентных запросов

Flow создания:

1. Проверить существующую связь `(task_id, JIRA)`.
2. Если статус `CREATED`, вернуть существующий Jira key и URL без нового вызова Jira.
3. Если связи нет, атомарно создать запись `CREATING`.
4. Уникальное ограничение должно защищать от двух параллельных запросов.
5. Выполнить вызов `JiraClient.createIssue`.
6. При успехе обновить запись до `CREATED`, сохранив id/key/url.
7. При ошибке обновить запись до `FAILED`, сохранив безопасное сообщение.
8. Повтор после `FAILED` должен быть явным действием пользователя и не должен создавать параллельные запросы.

Если Jira создала issue, но HTTP-ответ потерян, реализация должна минимизировать риск дубля. Для MVP допустимо:

- добавлять в description метку `LeaderOS Task ID: <id>`;
- использовать локальную запись `CREATING`;
- не выполнять автоматический retry create-запроса без явного решения.

## Изменения в API JavaMemoryService

Предлагаемый browser-facing API:

### Получение create context

```http
GET /api/tasks/{taskId}/jira/context
```

Ответ содержит:

- доступность интеграции;
- предзаполненные task fields;
- разрешённые проекты;
- issue types;
- текущего Jira-пользователя;
- существующую Jira-связь, если тикет уже создан.

### Создание issue

```http
POST /api/tasks/{taskId}/jira/issues
Content-Type: application/json
```

Пример request:

```json
{
  "projectKey": "TEAM",
  "issueType": "Task",
  "summary": "Подготовить релиз 3.12",
  "description": "Проверить образы и release notes",
  "assigneeId": "current-user"
}
```

Пример success response:

```json
{
  "status": "CREATED",
  "issueId": "100123",
  "issueKey": "TEAM-1842",
  "issueUrl": "https://jira.company.ru/browse/TEAM-1842"
}
```

Повторный запрос для уже связанной задачи должен вернуть существующую связь и не создавать новый issue.

### HTTP semantics

- `200 OK` — связь уже существовала или операция идемпотентно вернула существующий результат;
- `201 Created` — новый Jira issue успешно создан;
- `400 Bad Request` — невалидный project/type/assignee;
- `404 Not Found` — LeaderOS task не найдена;
- `409 Conflict` — операция уже находится в `CREATING` или требуется ручное решение после неоднозначного состояния;
- `503 Service Unavailable` — Jira disabled/unavailable.

## Изменения UI

### Today

На карточке задачи `/ui/today` добавить действие:

```text
Создать задачу в Jira
```

Если связь существует:

```text
Jira: TEAM-1842 ↗
```

Ссылка открывается в новой вкладке с безопасными `rel` attributes.

### Модальное окно

Поля MVP:

- проект — select только из allowlist;
- тип задачи;
- summary;
- description;
- исполнитель:
  - текущий Jira-пользователь;
  - без назначения.

Summary и description предзаполняются из LeaderOS task, но доступны для редактирования перед отправкой.

### Состояния UI

- Jira disabled — кнопка скрыта или disabled с пояснением;
- Jira unavailable — disabled и короткая причина;
- создание — кнопка блокируется, показывается progress;
- успех — modal закрывается, появляется ссылка на Jira;
- ошибка — modal остаётся открытой, показывается безопасное сообщение;
- уже создано — сразу показывается ссылка, create action недоступно.

## Исполнитель в MVP

Для реализованного MVP доступны только два варианта:

1. `Без назначения`
2. текущий Jira-пользователь (`/rest/api/2/myself`)

Явный mapping `LeaderOS Person -> Jira account` в MVP не входит и оставлен на следующий CR.

## Безопасность

- не логировать token, Authorization header и полный конфигурационный объект;
- не возвращать token через API или UI;
- не коммитить реальные credentials;
- проверять project key на сервере по allowlist, не доверяя UI;
- валидировать issue type для выбранного проекта;
- не позволять передавать произвольный Jira URL с клиента;
- ограничить размер summary/description до отправки в Jira;
- нормализовать Jira error responses перед показом пользователю.

## Наблюдаемость

Добавить безопасные логи:

- startup check result;
- task id, project key и operation result;
- Jira issue key после успеха;
- тип ошибки без credentials и Authorization headers.

Секреты и тело Authorization не должны попадать в логи даже на DEBUG.

## Зависимости

- JavaMemoryService зависит от `common` уже сейчас;
- использовать существующий HTTP stack проекта, не добавляя второй клиент без необходимости;
- учитывать различия Jira Cloud и Data Center, но реализовать только реально используемый тип авторизации, заданный конфигурацией MVP;
- точный REST API path и формат description должны соответствовать целевой Jira-инсталляции.

## Как тестировать

### Unit tests

1. `JiraIntegrationProperties` корректно биндуются из application properties.
2. Default project вне allowlist отклоняется.
3. JiraClient корректно формирует auth и create payload без утечки token в лог/exception.
4. Jira error responses преобразуются в доменные exceptions.
5. `JiraTaskService` возвращает существующую связь без повторного вызова клиента.
6. Два конкурентных запроса не создают две локальные связи.
7. Не разрешается project key вне allowlist.
8. Не разрешается недоступный issue type.

### Integration tests

Использовать mock HTTP server или WireMock-подобный механизм:

1. startup check success → `AVAILABLE`;
2. auth failure → `UNAVAILABLE`, MemoryService остаётся UP;
3. Jira timeout → `UNAVAILABLE`, MemoryService остаётся UP;
4. create issue success → связь `CREATED`;
5. create issue failure → связь `FAILED`;
6. повтор после success не вызывает Jira повторно.

### E2E scenario

Добавить новый сценарий в `JavaMemoryService/test_e2e/`, следующий по свободному номеру. Сценарий должен проверить:

1. UI `/ui/today` содержит Jira action при включённой mock-интеграции;
2. context endpoint возвращает только allowed projects;
3. создание Jira issue возвращает `201`;
4. связь сохраняется и отображается на Today;
5. повторный POST не создаёт дубль;
6. Jira unavailable не роняет JavaMemoryService и возвращает `503`;
7. cleanup удаляет только тестовые данные.

После реализации запустить соответствующий E2E-сценарий через `test-runner` и сохранить отчёт.

## Acceptance Criteria

- [ ] В `common` создана изолированная доменная область Jira без зависимости от Memory task/entity/UI.
- [ ] Все Jira-настройки MVP читаются из application properties JavaMemoryService.
- [ ] JavaMemoryService запускается при недоступной Jira.
- [ ] Startup check формирует безопасный статус `DISABLED | AVAILABLE | UNAVAILABLE`.
- [ ] Пользователь может открыть модальное окно из Today-задачи.
- [ ] UI показывает только проекты из server-side allowlist.
- [ ] Summary и description предзаполнены и редактируемы.
- [x] Пользователь может выбрать issue type и допустимого исполнителя.
- [ ] Успешно созданный Jira issue сохраняется как id/key/url.
- [ ] Для одной LeaderOS task нельзя создать два Jira issue через стандартный flow.
- [ ] Для уже связанной задачи показывается ссылка на Jira.
- [ ] Jira errors показываются без утечки credentials.
- [x] Добавлены unit/integration tests.
- [x] Добавлен E2E-сценарий.
- [x] Существующие Flyway migrations не изменены; добавлена только новая migration.
- [x] После подтверждения пользователя документация RFC и ARCHITECTURE.md приведена в соответствие, CR переведён в `Implemented`, реестр обновлён.

## Инструкция локальному агенту

1. Прочитать этот CR, `AGENT.md`, актуальные RFC `common` и JavaMemoryService и мастер-спеку `ARCHITECTURE.md`.
2. Не менять существующие файлы в `*/db/migration`; для таблицы создать новую migration со следующим свободным номером.
3. Реализовать изменение отдельными логическими коммитами.
4. Добавить unit/integration tests.
5. Добавить E2E-сценарий и запустить его через инструкции `test-runner/AGENT.md`.
6. Не менять статус CR на `Implemented` до пользовательской проверки.
7. После пользовательского подтверждения обновить RFC, `ARCHITECTURE.md`, статус CR и `docs/cr/REGISTRY.md`.
