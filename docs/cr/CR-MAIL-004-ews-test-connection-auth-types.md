# CR-MAIL-004: EWS Test Connection button and authentication type settings

**Дата:** 2026-06-24  
**Статус:** Draft  
**Сервис:** MAIL / MEM  
**Ветка:** `feature/mailAg-001`  
**Issue:** #19

## Проблема / Мотивация

В UI настроек подключения рабочей почты сейчас нет кнопки проверки соединения с корпоративным Exchange EWS endpoint.

Пользователь вводит параметры почты, например:

```text
https://outlook.domain.ru/EWS/Exchange.asmx
```

но не может сразу понять:

- доступен ли endpoint;
- является ли endpoint настоящим EWS service;
- корректны ли логин/пароль;
- какой тип авторизации нужен: `BASIC`, `NTLM`, `OAUTH2`;
- какая версия Exchange отвечает;
- сколько папок видит агент после подключения;
- сможет ли `JavaMailAgent` реально сканировать Inbox и подпапки.

Из-за этого ошибка обнаруживается поздно: только при запуске polling/scan цикла MailAgent.

Дополнительное наблюдение: при ручном открытии `Exchange.asmx` в браузере сервер может отвечать `HTTP 200` и страницей вида `Service / You have created a service`. Это хороший признак: endpoint опубликован и доступен по HTTPS, но это ещё не проверяет логин, пароль, NTLM и доступ к Inbox.

## Контекст текущей архитектуры

По текущей документации LeaderOS:

- `JavaMailAgent` отвечает за чтение почты;
- профили почты: `local → Maildev`, `dev → IMAP`, `prod → Exchange EWS`;
- `EwsMailClient` уже заявлен как реализованный клиент для prod;
- EWS должен рекурсивно сканировать `Inbox` и подпапки;
- настройки подключений пользователь хочет централизовать в `JavaMemoryService`, так как MemoryService становится ядром и точкой подключения плагинов.

## Решение

Добавить в настройки рабочей почты двухуровневую диагностику:

1. `Detect Endpoint` — быстрая проверка URL без авторизации.
2. `Test Connection` — полноценная проверка авторизации и доступа к mailbox/folders.

Также добавить:

1. Поле `Authentication Type`.
2. Backend endpoint для endpoint detection.
3. Backend endpoint для authenticated test connection.
4. Структурированный результат проверки, понятный пользователю.

Целевое отображение результата в UI:

```text
✓ Connected
Endpoint: OK
Authentication: OK
Exchange Version: 2019
Auth: NTLM
Mailbox: user@domain.ru
Inbox: OK
Folders found: 127
```

При ошибке:

```text
✗ Connection failed
Endpoint: OK
Auth: NTLM
Reason: Unauthorized / SSL handshake failed / endpoint not reachable / EWS response invalid
```

## Authentication Type

Добавить enum:

```java
public enum MailAuthType {
    BASIC,
    NTLM,
    OAUTH2
}
```

Для MVP обязательно реализовать:

- `BASIC`;
- `NTLM`.

`OAUTH2` добавить в модель и UI как planned/disabled или реализовать минимально, если уже есть корпоративные OAuth параметры.

Для `protocol = EWS` значение по умолчанию в UI должно быть:

```text
Authentication Type = NTLM
```

Причина: для корпоративного Exchange on-prem чаще всего используется NTLM, а не Basic.

## Изменения в UI

### Где

В текущей странице настроек подключения почты.

Если настройки сейчас находятся в `JavaMemoryService`, доработать страницу MemoryService settings.
Если настройки пока находятся в `JavaMailAgent`, не переносить их в рамках этого CR без отдельного CR; добавить кнопку там, где сейчас уже есть форма mail settings.

### Поля формы

Добавить/проверить наличие полей:

```text
Protocol: EWS / IMAP
EWS URL: https://outlook.domain.ru/EWS/Exchange.asmx
Username
Password
Authentication Type: BASIC | NTLM | OAUTH2
Folders include/exclude
```

При выборе `Protocol = EWS` UI должен автоматически выставлять `Authentication Type = NTLM`, если пользователь ещё не выбирал значение вручную.

### Кнопки

Добавить две кнопки:

```text
[Detect Endpoint]
[Test Connection]
```

#### Detect Endpoint

Проверяет только техническую доступность EWS URL без логина и пароля.

Поведение:

- делает lightweight HTTP GET/HEAD к `Exchange.asmx`;
- проверяет `HTTP 200` / `401` / корректный WCF/EWS response;
- не требует username/password;
- не пытается читать mailbox;
- не запускает polling;
- не сохраняет настройки.

Успешный UI result:

```text
✓ Endpoint detected
Endpoint: OK
HTTPS: OK
EWS Service: Detected
Recommended Auth: NTLM
```

Если сервер отвечает страницей `Service / You have created a service`, считать это успешным признаком `EWS Service: Detected`, потому что это типичный WCF/EWS service landing response.

#### Test Connection

Проверяет полноценное подключение с учётом `Authentication Type`.

Поведение:

- кнопка не запускает polling;
- кнопка не сохраняет письмо в processed;
- кнопка не меняет read/unread статус писем;
- кнопка делает authenticated bind к mailbox/Inbox;
- во время проверки показывает loading state;
- результат показывает inline-блоком под формой;
- пароль в UI и логах не отображается.

## Изменения в API

Добавить endpoint для проверки endpoint без авторизации.

Рекомендуемый вариант, если настройки централизованы в MemoryService:

```http
POST /api/settings/mail/detect-endpoint
Content-Type: application/json
```

Request:

```json
{
  "protocol": "EWS",
  "ewsUrl": "https://outlook.domain.ru/EWS/Exchange.asmx"
}
```

Response success:

```json
{
  "status": "DETECTED",
  "protocol": "EWS",
  "endpointReachable": true,
  "httpsOk": true,
  "ewsDetected": true,
  "httpStatus": 200,
  "recommendedAuthType": "NTLM",
  "message": "EWS endpoint detected"
}
```

Response error:

```json
{
  "status": "FAILED",
  "protocol": "EWS",
  "endpointReachable": false,
  "httpsOk": false,
  "ewsDetected": false,
  "errorType": "ENDPOINT_NOT_REACHABLE",
  "message": "EWS endpoint is not reachable"
}
```

Добавить endpoint для полноценной проверки подключения.

Рекомендуемый вариант, если настройки централизованы в MemoryService:

```http
POST /api/settings/mail/test-connection
Content-Type: application/json
```

Request:

```json
{
  "protocol": "EWS",
  "ewsUrl": "https://outlook.domain.ru/EWS/Exchange.asmx",
  "username": "user@domain.ru",
  "password": "***",
  "authType": "NTLM",
  "folderExclude": ["Inbox/CI/CD"]
}
```

Response success:

```json
{
  "status": "CONNECTED",
  "protocol": "EWS",
  "endpointReachable": true,
  "httpsOk": true,
  "ewsDetected": true,
  "authenticationOk": true,
  "exchangeVersion": "2019",
  "authType": "NTLM",
  "mailbox": "user@domain.ru",
  "inboxFound": true,
  "foldersFound": 127,
  "foldersScanLimited": false,
  "message": "Connected"
}
```

Response error:

```json
{
  "status": "FAILED",
  "protocol": "EWS",
  "endpointReachable": true,
  "httpsOk": true,
  "ewsDetected": true,
  "authenticationOk": false,
  "authType": "NTLM",
  "errorType": "UNAUTHORIZED",
  "message": "EWS authentication failed",
  "details": "HTTP 401 Unauthorized"
}
```

Если настройки остаются в MailAgent, допустимые endpoint-ы:

```http
POST /api/mail/settings/detect-endpoint
POST /api/mail/settings/test-connection
```

Важно: выбрать один вариант endpoint-ов и зафиксировать его в README/ARCHITECTURE после реализации.

## Backend дизайн

### DTO

Добавить DTO:

```java
MailEndpointDetectRequest
MailEndpointDetectResult
MailConnectionTestRequest
MailConnectionTestResult
MailConnectionStatus
MailAuthType
MailProtocol
```

### Service

Добавить сервис:

```java
MailConnectionTestService
```

Ответственность:

- валидировать request;
- выбрать нужный protocol tester;
- выполнить endpoint detection;
- вызвать EWS test client;
- вернуть структурированный результат;
- не запускать полноценный polling.

### EWS endpoint detector

Добавить компонент:

```java
EwsEndpointDetector
```

Логика:

1. Проверить, что URL валиден и заканчивается на `/EWS/Exchange.asmx` или совместимый EWS endpoint.
2. Выполнить lightweight HTTP GET/HEAD.
3. Считать endpoint успешным, если:
   - HTTP 200 и body содержит признаки WCF/EWS service (`Service`, `You have created a service`, `wsdl`, `Exchange Web Services`);
   - или HTTP 401/403, но endpoint отвечает как защищённый EWS/WCF service.
4. Вернуть `recommendedAuthType = NTLM` для EWS, если нет более точной информации.

### EWS tester

Добавить компонент:

```java
EwsConnectionTester
```

Логика:

1. Создать `ExchangeService`.
2. Настроить endpoint URL.
3. Настроить credentials в зависимости от `authType`.
4. Выполнить lightweight authenticated запрос:
   - bind к Inbox;
   - получить server info / inferred Exchange version;
   - рекурсивно или ограниченно посчитать папки.
5. Вернуть `MailConnectionTestResult`.

### Folder count

Для `foldersFound` использовать тот же подход, который уже применяет `EwsMailClient` для сканирования `Inbox` и подпапок.

Если полный рекурсивный обход может быть дорогим, добавить ограничение:

```yaml
mail:
  test-connection:
    max-folders-to-scan: 500
    timeout-seconds: 15
```

В ответе добавить флаг:

```json
{
  "foldersFound": 127,
  "foldersScanLimited": false
}
```

## Логирование и безопасность

Запрещено логировать:

- пароль;
- access token;
- refresh token;
- полный Authorization header.

Разрешено логировать:

```text
EWS endpoint detection started: endpoint=https://outlook.domain.ru/EWS/Exchange.asmx
EWS endpoint detected: httpStatus=200, ewsDetected=true, recommendedAuthType=NTLM
EWS test connection started: endpoint=https://outlook.domain.ru/EWS/Exchange.asmx, authType=NTLM, username=user@domain.ru
EWS test connection success: exchangeVersion=2019, foldersFound=127
EWS test connection failed: errorType=UNAUTHORIZED, message=HTTP 401 Unauthorized
```

Для username желательно маскирование в UI/логах, если уже есть общий masking utility.

## Изменения в схеме БД

Если mail settings уже сохраняются в БД, добавить поле:

```sql
auth_type varchar(32) not null default 'NTLM'
```

Если settings поддерживают разные protocol, правило default такое:

- `EWS` → `NTLM`;
- `IMAP` → `BASIC` / `LOGIN`, в зависимости от текущей модели.

Если settings пока хранятся только в config/yaml, миграция БД не требуется.

Нельзя хранить пароль в plain text в PostgreSQL без отдельного решения по secrets.
Для MVP допустимо:

- принимать пароль только для test request;
- не сохранять пароль;
- сохранять только non-secret настройки.

## Изменения в конфигурации

Добавить пример в `application-local.yml.example` / `application-prod.yml.example`:

```yaml
mail:
  protocol: ews
  ews:
    url: https://outlook.domain.ru/EWS/Exchange.asmx
    username: user@domain.ru
    auth-type: NTLM # BASIC | NTLM | OAUTH2
  test-connection:
    timeout-seconds: 15
    max-folders-to-scan: 500
```

## Изменения в документации

Обновить:

- `README.md` — раздел подключения рабочей почты;
- `ARCHITECTURE.md` — MailAgent protocol/auth settings;
- при наличии отдельного `JavaMailAgent/README.md` — добавить пример EWS настройки;
- при наличии `test_e2e/` — добавить сценарий проверки подключения.

## Как тестировать

### Unit tests

Добавить тесты:

1. `MailAuthType` корректно парсит `BASIC`, `NTLM`, `OAUTH2`.
2. `MailConnectionTestService` возвращает `FAILED`, если URL пустой.
3. `MailConnectionTestService` не логирует password.
4. `EwsEndpointDetector` определяет `HTTP 200 + You have created a service` как `EWS detected`.
5. `EwsEndpointDetector` возвращает `recommendedAuthType = NTLM` для EWS.
6. `EwsConnectionTester` мапит ошибки:
   - `401` → `UNAUTHORIZED`;
   - timeout → `TIMEOUT`;
   - SSL error → `SSL_ERROR`;
   - malformed URL → `INVALID_ENDPOINT`.

### UI smoke test

Проверить:

1. Открыть settings page.
2. Выбрать protocol `EWS`.
3. Убедиться, что default `Authentication Type = NTLM`.
4. Нажать `Detect Endpoint`.
5. Увидеть `Endpoint: OK`, `HTTPS: OK`, `EWS Service: Detected`, `Recommended Auth: NTLM`.
6. Ввести username/password.
7. Нажать `Test Connection`.
8. Увидеть structured result.

### E2E scenario

Добавить Markdown-сценарий:

```text
JavaMailAgent/test_e2e/08_ews_test_connection.md
```

или, если settings живут в MemoryService:

```text
JavaMemoryService/test_e2e/12_mail_settings_test_connection.md
```

Сценарий должен проверять:

- endpoint существует;
- detector распознаёт fake response `Service / You have created a service`;
- request валидируется;
- mock/fake EWS возвращает success;
- UI отображает `Endpoint detected`, `Connected`, `Auth`, `Folders found`.

Для локального e2e не использовать реальный корпоративный Exchange.
Нужен mock/stub EWS server или unit-level fake `EwsConnectionTester`.

## Acceptance Criteria

- [ ] В UI настроек почты есть selector `Authentication Type` со значениями `BASIC`, `NTLM`, `OAUTH2`.
- [ ] При выборе `Protocol = EWS` default auth type становится `NTLM`.
- [ ] В UI есть кнопка `Detect Endpoint`.
- [ ] `Detect Endpoint` без логина/пароля показывает `Endpoint`, `HTTPS`, `EWS Service`, `Recommended Auth`.
- [ ] `HTTP 200` + `Service / You have created a service` считается успешным EWS endpoint detection.
- [ ] В UI есть кнопка `Test Connection`.
- [ ] Нажатие `Test Connection` вызывает backend endpoint и не запускает mail polling.
- [ ] Для успешного EWS подключения UI показывает: `Connected`, `Endpoint`, `Authentication`, `Exchange Version`, `Auth`, `Mailbox`, `Inbox`, `Folders found`.
- [ ] Для ошибки UI показывает понятную причину без stacktrace.
- [ ] Пароль/token не попадает в UI, логи и response body.
- [ ] `BASIC` и `NTLM` реально поддержаны в backend.
- [ ] `OAUTH2` либо реализован, либо отображается как planned/disabled с понятным текстом.
- [ ] Добавлены unit tests на detector, service и error mapping.
- [ ] Добавлен e2e или smoke сценарий для кнопок.
- [ ] Обновлены README/ARCHITECTURE.

## Out of scope

- Полный OAuth2 flow через Microsoft Entra ID.
- Хранение корпоративных секретов в БД.
- Автоматический подбор auth type перебором логина/пароля.
- Изменение основного polling flow MailAgent.
- Перенос всех настроек между MemoryService и MailAgent, если это требует отдельной миграции.

## Риски

1. **NTLM может потребовать domain/workstation.**  
   Нужно предусмотреть формат `DOMAIN\\user` или отдельное поле `domain`.

2. **EWS Java API может не отдавать точную Exchange version.**  
   Если точную версию нельзя получить стабильно, вернуть `UNKNOWN` и показать это в UI.

3. **Корпоративный SSL может быть с внутренним CA.**  
   Ошибку SSL нужно показывать отдельно: `SSL_ERROR`, а не как общий `FAILED`.

4. **Полный scan папок может быть долгим.**  
   Нужен timeout и max folder scan limit.

5. **HTTP 200 без auth не означает успешное подключение к mailbox.**  
   Поэтому UI должен явно разделять `Endpoint detected` и `Connected`.

## Рекомендация по реализации для агента

1. Найти текущую страницу settings и DTO настроек почты.
2. Добавить `MailAuthType` в модель настроек.
3. При `Protocol = EWS` выставить default `NTLM`.
4. Добавить backend endpoint для `detect-endpoint`.
5. Добавить backend endpoint для `test-connection`.
6. Реализовать `EwsEndpointDetector`.
7. Реализовать `EwsConnectionTester` на базе существующего `EwsMailClient`.
8. Добавить кнопки и result blocks в UI.
9. Добавить tests.
10. Обновить docs.
11. Прогнать:

```bash
mvn -pl JavaMailAgent test
mvn -pl JavaMemoryService test
./test-runner/healthcheck.sh
```

Если settings живут только в одном сервисе, тестировать только затронутый сервис + интеграционный smoke.
