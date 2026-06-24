# CR-MAIL-004: EWS Test Connection button and authentication type settings

**Дата:** 2026-06-24  
**Статус:** Draft  
**Сервис:** MAIL / MEM  
**Ветка:** `feature/mailAg-001`  
**Issue:** создаётся отдельно после CR

## Проблема / Мотивация

В UI настроек подключения рабочей почты сейчас нет кнопки проверки соединения с корпоративным Exchange EWS endpoint.

Пользователь вводит параметры почты, например:

```text
https://outlook.domain.ru/EWS/Exchange.asmx
```

но не может сразу понять:

- доступен ли endpoint;
- корректны ли логин/пароль;
- какой тип авторизации нужен: `BASIC`, `NTLM`, `OAUTH2`;
- какая версия Exchange отвечает;
- сколько папок видит агент после подключения;
- сможет ли `JavaMailAgent` реально сканировать Inbox и подпапки.

Из-за этого ошибка обнаруживается поздно: только при запуске polling/scan цикла MailAgent.

## Контекст текущей архитектуры

По текущей документации LeaderOS:

- `JavaMailAgent` отвечает за чтение почты;
- профили почты: `local → Maildev`, `dev → IMAP`, `prod → Exchange EWS`;
- `EwsMailClient` уже заявлен как реализованный клиент для prod;
- EWS должен рекурсивно сканировать `Inbox` и подпапки;
- настройки подключений пользователь хочет централизовать в `JavaMemoryService`, так как MemoryService становится ядром и точкой подключения плагинов.

## Решение

Добавить в настройки рабочей почты:

1. Поле `Authentication Type`.
2. Кнопку `Test Connection`.
3. Backend endpoint, который делает реальную проверку подключения к EWS.
4. Структурированный результат проверки, понятный пользователю.

Целевое отображение результата в UI:

```text
✓ Connected
Exchange Version: 2019
Auth: NTLM
Folders found: 127
```

При ошибке:

```text
✗ Connection failed
Auth: NTLM
Endpoint: https://outlook.domain.ru/EWS/Exchange.asmx
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

### Кнопка

Добавить кнопку:

```text
[Test Connection]
```

Поведение:

- кнопка не запускает polling;
- кнопка не сохраняет письмо в processed;
- кнопка не меняет read/unread статус писем;
- кнопка только проверяет техническое подключение;
- во время проверки показывает loading state;
- результат показывает inline-блоком под формой;
- пароль в UI и логах не отображается.

## Изменения в API

Добавить endpoint для проверки подключения.

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
  "exchangeVersion": "2019",
  "authType": "NTLM",
  "foldersFound": 127,
  "inboxFound": true,
  "message": "Connected"
}
```

Response error:

```json
{
  "status": "FAILED",
  "protocol": "EWS",
  "authType": "NTLM",
  "errorType": "UNAUTHORIZED",
  "message": "EWS authentication failed",
  "details": "HTTP 401 Unauthorized"
}
```

Если настройки остаются в MailAgent, допустимый endpoint:

```http
POST /api/mail/settings/test-connection
```

Важно: выбрать один endpoint и зафиксировать его в README/ARCHITECTURE после реализации.

## Backend дизайн

### DTO

Добавить DTO:

```java
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
- вызвать EWS test client;
- вернуть структурированный результат;
- не запускать полноценный polling.

### EWS tester

Добавить компонент:

```java
EwsConnectionTester
```

Логика:

1. Создать `ExchangeService`.
2. Настроить endpoint URL.
3. Настроить credentials в зависимости от `authType`.
4. Выполнить lightweight запрос:
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
EWS test connection started: endpoint=https://outlook.domain.ru/EWS/Exchange.asmx, authType=NTLM, username=user@domain.ru
EWS test connection success: exchangeVersion=2019, foldersFound=127
EWS test connection failed: errorType=UNAUTHORIZED, message=HTTP 401 Unauthorized
```

Для username желательно маскирование в UI/логах, если уже есть общий masking utility.

## Изменения в схеме БД

Если mail settings уже сохраняются в БД, добавить поле:

```sql
auth_type varchar(32) not null default 'BASIC'
```

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
4. `EwsConnectionTester` мапит ошибки:
   - `401` → `UNAUTHORIZED`;
   - timeout → `TIMEOUT`;
   - SSL error → `SSL_ERROR`;
   - malformed URL → `INVALID_ENDPOINT`.

### UI smoke test

Проверить:

1. Открыть settings page.
2. Выбрать protocol `EWS`.
3. Увидеть поле `Authentication Type`.
4. Выбрать `NTLM`.
5. Нажать `Test Connection`.
6. Увидеть structured result.

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
- request валидируется;
- mock/fake EWS возвращает success;
- UI отображает `Connected`, `Auth`, `Folders found`.

Для локального e2e не использовать реальный корпоративный Exchange.
Нужен mock/stub EWS server или unit-level fake `EwsConnectionTester`.

## Acceptance Criteria

- [ ] В UI настроек почты есть selector `Authentication Type` со значениями `BASIC`, `NTLM`, `OAUTH2`.
- [ ] В UI есть кнопка `Test Connection`.
- [ ] Нажатие кнопки вызывает backend endpoint и не запускает mail polling.
- [ ] Для успешного EWS подключения UI показывает: `Connected`, `Exchange Version`, `Auth`, `Folders found`.
- [ ] Для ошибки UI показывает понятную причину без stacktrace.
- [ ] Пароль/token не попадает в UI, логи и response body.
- [ ] `BASIC` и `NTLM` реально поддержаны в backend.
- [ ] `OAUTH2` либо реализован, либо отображается как planned/disabled с понятным текстом.
- [ ] Добавлены unit tests на service и error mapping.
- [ ] Добавлен e2e или smoke сценарий для кнопки.
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

## Рекомендация по реализации для агента

1. Найти текущую страницу settings и DTO настроек почты.
2. Добавить `MailAuthType` в модель настроек.
3. Добавить backend endpoint для test connection.
4. Реализовать `EwsConnectionTester` на базе существующего `EwsMailClient`.
5. Добавить кнопку и result block в UI.
6. Добавить tests.
7. Обновить docs.
8. Прогнать:

```bash
mvn -pl JavaMailAgent test
mvn -pl JavaMemoryService test
./test-runner/healthcheck.sh
```

Если settings живут только в одном сервисе, тестировать только затронутый сервис + интеграционный smoke.
