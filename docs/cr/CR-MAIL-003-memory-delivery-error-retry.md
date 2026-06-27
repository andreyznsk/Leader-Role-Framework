# CR-MAIL-003: MemoryService delivery error retry flow

**Дата:** 2026-06-25
**Статус:** Draft
**Сервис:** MAIL
**Зависимости:** JavaMailAgent, JavaMemoryService

## Проблема / Мотивация

Сейчас JavaMailAgent читает письма, классифицирует их и по `response.type()` выполняет детерминированный `switch`:

```java
switch (response.type()) {
    case REQUEST -> {
        Files.writeString(planFile, "\n" + response.taskLine(), ...);
        memoryServiceClient.createPendingTask(...);
        moveToProcessedIfEnabled(...);
        return new ActionResult(null, false);
    }
    case CAPTURE -> {
        memoryServiceClient.createCapture(text, "email");
        moveToProcessedIfEnabled(...);
        return new ActionResult(null, false);
    }
    case NOTICE -> {
        Path noticePath = noticeDocumentWriter.write(email, response.note());
        moveToProcessedIfEnabled(...);
        return new ActionResult(noticePath.toString(), true);
    }
}
```

Проблема: ошибка может произойти не в начале обработки письма, а внутри конкретного route/action.

Пример для `REQUEST`:

1. `Files.writeString(planFile, ...)` уже выполнился;
2. `memoryServiceClient.createPendingTask(...)` упал;
3. если при retry заново выполнить весь `REQUEST` route, строка в `plans/today.md` продублируется.

Нужен не просто retry письма целиком, а retry **с того места, где упали**.

Цель — добиться at-least-once доставки полезного результата в MemoryService без потери писем и без дублей локальных side-effect-ов: записей в план, файлов NOTICE, черновиков, перемещений в `processed/`.

## Решение

### 1. Добавить статус и checkpoint обработки письма

Расширить состояние обработки письма в БД MailAgent.

Минимальная state-machine:

```text
NEW / PROCESSING / ERROR / PROCESSED
```

Но одного `status = ERROR` недостаточно. Нужно сохранять checkpoint: какой route упал и какой payload нужно повторить.

Рекомендуемая модель:

```text
mailagent.processed_emails
- id
- message_id
- subject
- sender
- response_type              -- REQUEST / DRAFT / NOISE / CAPTURE / NOTICE
- status                     -- NEW / PROCESSING / ERROR / PROCESSED
- failed_route               -- конкретный route/checkpoint для retry
- route_payload_json         -- JSON payload для повторения route без повторной классификации
- action_result_json         -- опционально: результат уже выполненного action
- error_message
- attempts_count
- first_seen_at
- last_attempt_at
- processed_at
- created_at
- updated_at
```

### 2. Ввести route/checkpoint enum

Добавить enum для точек повтора:

```text
PLAN_APPEND
MEMORY_PENDING_TASK
MEMORY_CAPTURE
NOTICE_WRITE
DRAFT_WRITE
MOVE_TO_PROCESSED
MARK_AS_READ
NONE
```

На MVP можно начать с реально опасных точек:

```text
MEMORY_PENDING_TASK
MEMORY_CAPTURE
MOVE_TO_PROCESSED
```

Но CR должен закладывать расширяемую модель, потому что side-effect-и есть не только у MemoryService.

### 3. Сохранять route перед выполнением опасного шага

Перед каждым side-effect-ом, который может упасть, MailAgent должен сохранить в БД:

```text
status = PROCESSING
response_type = <тип ответа>
failed_route = <route, который сейчас будет выполняться>
route_payload_json = <payload, достаточный для retry>
last_attempt_at = now()
```

После успешного выполнения route:

- если есть следующий route — обновить `failed_route` на следующий route;
- если route был последним — поставить `status = PROCESSED`.

### 4. Поведение на примере REQUEST

Текущий route:

```java
case REQUEST -> {
    Files.writeString(planFile, "\n" + response.taskLine(), ...);

    memoryServiceClient.createPendingTask(new PendingTaskRequest(
        response.taskTitle(),
        response.note(),
        response.emailId(),
        response.sender(),
        response.priority()
    ));

    moveToProcessedIfEnabled(...);
    return new ActionResult(null, false);
}
```

Должен стать логически таким:

```text
REQUEST route:

1. checkpoint PLAN_APPEND + payload(taskLine, planPath)
2. append строку в plan file
3. checkpoint MEMORY_PENDING_TASK + payload(PendingTaskRequest)
4. POST /api/tasks/pending
5. checkpoint MOVE_TO_PROCESSED + payload(inbox, processed, moveEnabled)
6. moveToProcessedIfEnabled(...)
7. mark PROCESSED
```

Если ошибка произошла на шаге 4, в БД остаётся:

```text
status = ERROR
response_type = REQUEST
failed_route = MEMORY_PENDING_TASK
route_payload_json = PendingTaskRequest JSON
```

Следующий запуск job не должен заново выполнять:

```text
PLAN_APPEND
```

Он должен выполнить только:

```text
MEMORY_PENDING_TASK -> MOVE_TO_PROCESSED -> PROCESSED
```

### 5. Retry-first flow

При каждом запуске job обработки почты порядок должен быть таким:

```text
1. Найти записи status = ERROR.
2. Если ERROR есть — обрабатывать их первыми по last_attempt_at ASC / created_at ASC.
3. Для ERROR не читать письмо заново из почты и не вызывать LLM повторно.
4. Для ERROR выполнить retry по saved failed_route + route_payload_json.
5. Если ERROR очередь пустая — читать новые письма из почтового ящика.
6. Если при обработке любого route произошла ошибка — поставить ERROR и остановить текущий job-run.
```

Важно: `ERROR` письма должны быть в начале очереди следующего запуска, а не смешиваться с новыми письмами.

### 6. Поведение при ошибке route

Ошибка route — это любое из условий:

- HTTP 5xx от MemoryService;
- HTTP 4xx, если тело/контракт не принято;
- timeout;
- connection refused;
- exception клиента;
- IOException при записи файла;
- IOException при move в `processed/`;
- некорректный ответ, если клиент ожидает `2xx`.

При такой ошибке MailAgent должен:

1. сохранить/обновить запись письма в БД;
2. поставить `status = ERROR`;
3. сохранить `response_type`;
4. сохранить `failed_route`;
5. сохранить `route_payload_json`;
6. увеличить `attempts_count`;
7. записать `error_message` и `last_attempt_at`;
8. не помечать письмо прочитанным на почтовом сервере, если route до этого не дошёл;
9. не выполнять следующие route-и;
10. остановить дальнейшую обработку текущего batch/job-run.

### 7. Поведение при успешном retry

Если retry упавшего route успешен:

- выполнить следующий route в цепочке;
- перед следующим route обновить `failed_route` и `route_payload_json`;
- если вся цепочка завершилась — `status = PROCESSED`, `processed_at = now()`;
- `error_message = null` или перенести старую ошибку в отдельное поле/лог;
- письмо больше не попадает в retry-очередь.

### 8. Не перечитывать и не переклассифицировать ERROR письма

Для записей `status = ERROR` запрещено:

- повторно читать письмо из почтового сервера как новое;
- повторно вызывать LLM-классификацию;
- заново выполнять уже успешные side-effect-и;
- заново писать строку в `plans/today.md`, если ошибка была после `PLAN_APPEND`;
- заново писать NOTICE-файл, если ошибка была после `NOTICE_WRITE`;
- заново создавать DRAFT-файл, если ошибка была после `DRAFT_WRITE`.

Retry должен быть основан на сохранённом `response_type`, `failed_route`, `route_payload_json`.

### 9. Идемпотентность

Так как часть внешних вызовов может выполниться, но ответ не дойти до MailAgent, retry всё равно должен быть безопасным.

Требования:

- MailAgent должен передавать стабильный внешний идентификатор письма в MemoryService (`messageId` / `emailId`).
- JavaMemoryService должен быть устойчив к повторному запросу с тем же `emailId`.
- Если MemoryService уже создал задачу/заметку по этому письму, повторный запрос должен вернуть успешный результат или конфликт, который MailAgent трактует как успешную доставку.

Минимальный вариант для MVP:

- для `REQUEST` использовать существующее поле `emailId` в `/api/tasks/pending`;
- для `CAPTURE` добавить/использовать `sourceId` или `emailId` в `/api/capture`, если такого поля ещё нет.

## Изменения в API

### JavaMailAgent internal API / service layer

Добавить сервисный flow:

```text
MailProcessingJob
  -> processErrorQueueFirst()
  -> if no errors: fetchNewEmails()
  -> processEmail(email)
```

Для `ERROR` flow:

```text
processErrorQueueFirst()
  -> load ERROR records
  -> retryFromCheckpoint(record.responseType, record.failedRoute, record.routePayloadJson)
  -> continue route chain after successful checkpoint
```

Добавить/обновить методы репозитория:

```text
findByStatusOrderByLastAttemptAtAscCreatedAtAsc(ERROR)
markProcessing(messageId, responseType, failedRoute, routePayloadJson)
markCheckpoint(messageId, failedRoute, routePayloadJson)
markProcessed(messageId, responseType)
markError(messageId, responseType, failedRoute, routePayloadJson, errorMessage)
existsProcessed(messageId)
```

Добавить сервис/компонент:

```text
MailActionCheckpointService
  - checkpoint(...)
  - markError(...)
  - markProcessed(...)
```

Добавить retry executor:

```text
MailRouteRetryExecutor
  - retry(record)
  - retryRequestFrom(route, payload)
  - retryCaptureFrom(route, payload)
  - retryNoticeFrom(route, payload)
```

### JavaMemoryService API

Контракт `/api/tasks/pending` должен сохранять идемпотентность по `emailId`.

Для `/api/capture` нужно проверить наличие внешнего идентификатора источника. Если его нет — добавить поле:

```json
{
  "text": "...",
  "source": "mail",
  "sourceId": "<mail-message-id>"
}
```

## Изменения в схеме БД

Создать Flyway migration в JavaMailAgent.

Предлагаемые изменения:

```sql
ALTER TABLE mailagent.processed_emails
  ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
  ADD COLUMN IF NOT EXISTS response_type VARCHAR(32),
  ADD COLUMN IF NOT EXISTS failed_route VARCHAR(64),
  ADD COLUMN IF NOT EXISTS route_payload_json TEXT,
  ADD COLUMN IF NOT EXISTS action_result_json TEXT,
  ADD COLUMN IF NOT EXISTS error_message TEXT,
  ADD COLUMN IF NOT EXISTS attempts_count INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS first_seen_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_processed_emails_status_attempt
  ON mailagent.processed_emails(status, last_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_processed_emails_failed_route
  ON mailagent.processed_emails(failed_route);
```

Если текущая структура `processed_emails` не подходит для state-machine, разрешается создать новую таблицу:

```text
mailagent.email_processing_state
```

Но предпочтительно не плодить сущности без необходимости.

## Зависимости от других сервисов

### JavaMemoryService

Нужно проверить/доработать идемпотентность:

- `POST /api/tasks/pending` по `emailId`;
- `POST /api/capture` по `sourceId/emailId`, если capture создаётся из письма.

### JavaMailAgent

Нужно проверить клиентов:

- `MemoryServiceClient` должен бросать контролируемое исключение при не-2xx ответе;
- `ActionExecutor` не должен глотать ошибки доставки;
- `ActionExecutor` должен выставлять checkpoint перед каждым опасным side-effect-ом;
- `PollScheduler` / mail job должен уметь останавливать текущий batch после ошибки;
- retry executor должен уметь продолжать route chain после успешного checkpoint.

## Как тестировать

### Unit tests

1. `MemoryServiceClient`:
   - при `2xx` возвращает success;
   - при `5xx`, timeout, connection refused бросает exception.

2. `ActionExecutor` для `REQUEST`:
   - перед append в plan сохраняет `failed_route = PLAN_APPEND`;
   - после успешного append перед вызовом MemoryService сохраняет `failed_route = MEMORY_PENDING_TASK`;
   - если `createPendingTask` падает, запись получает:
     - `status = ERROR`;
     - `response_type = REQUEST`;
     - `failed_route = MEMORY_PENDING_TASK`;
     - `route_payload_json = PendingTaskRequest JSON`;
   - retry с `MEMORY_PENDING_TASK` не пишет повторно строку в plan.

3. `ActionExecutor` для `CAPTURE`:
   - если `createCapture` падает, запись получает:
     - `status = ERROR`;
     - `response_type = CAPTURE`;
     - `failed_route = MEMORY_CAPTURE`;
     - `route_payload_json = capture payload JSON`.

4. `MailProcessingJob`:
   - сначала выбирает `ERROR` письма;
   - не читает новые письма, пока есть `ERROR`;
   - не вызывает LLM для `ERROR` записей;
   - останавливает batch после новой ошибки route.

5. `MailRouteRetryExecutor`:
   - retry `REQUEST + MEMORY_PENDING_TASK` вызывает только MemoryService и последующие route-и;
   - retry `CAPTURE + MEMORY_CAPTURE` вызывает только MemoryService и последующие route-и;
   - успешный retry переводит запись в `PROCESSED`.

### E2E сценарий

Добавить сценарий:

```text
JavaMailAgent/test_e2e/08_memory_delivery_error_retry.md
```

Шаги:

1. Запустить MailAgent и Maildev.
2. Остановить JavaMemoryService или подменить URL на недоступный порт.
3. Отправить письмо типа REQUEST в Maildev.
4. Запустить poll/job обработки.
5. Проверить в БД MailAgent:
   - письмо имеет `status = ERROR`;
   - `response_type = REQUEST`;
   - `failed_route = MEMORY_PENDING_TASK`;
   - `route_payload_json` содержит `PendingTaskRequest`;
   - `attempts_count = 1`;
   - `error_message` заполнен.
6. Проверить, что строка в `plans/today.md` появилась только один раз.
7. Проверить, что письмо не помечено read/processed на почтовом сервере, если route до этого не дошёл.
8. Отправить второе новое письмо.
9. Запустить JavaMemoryService.
10. Запустить следующий poll/job.
11. Проверить, что первым обработано письмо из `ERROR`.
12. Проверить, что задача появилась в MemoryService.
13. Проверить, что строка в `plans/today.md` не продублировалась.
14. Проверить, что `status = PROCESSED`.
15. Проверить, что только после этого обработано второе письмо.

### Acceptance Criteria

- Если route падает, MailAgent сохраняет письмо со статусом `ERROR`.
- В `ERROR` записи сохраняются `response_type`, `failed_route`, `route_payload_json`, `error_message`, `attempts_count`.
- После `ERROR` MailAgent прекращает дальнейшее чтение/обработку новых писем в текущем запуске job.
- При следующем запуске job письма со статусом `ERROR` обрабатываются первыми.
- Для `ERROR` письма MailAgent не перечитывает письмо из почтового сервера и не вызывает LLM повторно.
- Retry выполняется с сохранённого `failed_route`, а не с начала `switch(response.type())`.
- Для `REQUEST`, если падение было на `MEMORY_PENDING_TASK`, retry не добавляет повторную строку в `plans/today.md`.
- Успешный retry переводит письмо из `ERROR` в `PROCESSED`.
- Повторная доставка не создаёт дубль задачи/заметки в MemoryService.
- Для `NOISE` логика не ломается: письмо может быть помечено прочитанным только после успешного завершения действия.
- Для `DRAFT` логика не ломается: черновик не должен создаваться дублем при retry.
- Для `NOTICE` логика не ломается: NOTICE-документ не должен создаваться дублем при retry.
- Добавлен E2E сценарий `08_memory_delivery_error_retry.md`.
- Обновлена документация по JavaMailAgent flow в `ARCHITECTURE.md` или RFC MailAgent.

## Риски / Вопросы

1. Нужно проверить текущую семантику `processed_emails`: если таблица сейчас означает только дедупликацию, расширение до state-machine может потребовать аккуратной миграции.
2. Нужно решить, считать ли HTTP `409 Conflict` от MemoryService успешным результатом retry, если объект уже создан ранее.
3. Для `DRAFT` нужен отдельный guard от дублей черновиков, если ошибка случится после записи файла, но до финального статуса.
4. Для `NOTICE` нужен отдельный guard от дублей NOTICE-документов, если ошибка случится после `noticeDocumentWriter.write(...)`, но до `moveToProcessedIfEnabled(...)`.
5. Для `PLAN_APPEND` нужен способ не дублировать строку: либо checkpoint после успешного append, либо идемпотентная запись с маркером `emailId` в строке плана.

## Инструкция для локального агента

1. Прочитать этот CR.
2. Найти текущий flow обработки писем в JavaMailAgent, особенно `switch(response.type())` в `ActionExecutor`.
3. Найти текущую структуру `processed_emails` и миграции Flyway.
4. Реализовать state-machine обработки писем.
5. Добавить поля `failed_route` и `route_payload_json`.
6. Перед каждым опасным side-effect-ом сохранять checkpoint в БД.
7. Добавить retry-first очередь для `ERROR`.
8. Для `ERROR` retry выполнять с сохранённого route/checkpoint, а не с начала `switch`.
9. Обеспечить остановку batch/job-run после ошибки route.
10. Проверить идемпотентность MemoryService по `emailId/sourceId`.
11. Добавить unit и E2E тесты.
12. Обновить документацию.
13. Прогнать тесты JavaMailAgent и интеграционный сценарий email → MemoryService.
