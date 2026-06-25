# CR-MAIL-003: MemoryService delivery error retry flow

**Дата:** 2026-06-25
**Статус:** Draft
**Сервис:** MAIL
**Зависимости:** JavaMailAgent, JavaMemoryService

## Проблема / Мотивация

Сейчас JavaMailAgent читает письма, классифицирует их и для полезных типов выполняет действие в JavaMemoryService:

- `REQUEST` → `POST /api/tasks/pending`
- `CAPTURE` → `POST /api/capture`

Если агент уже прочитал и классифицировал письмо, но не смог сохранить результат в JavaMemoryService, письмо не должно теряться и не должно считаться успешно обработанным.

Нужен явный error-flow:

1. письмо получает статус `ERROR` в базе MailAgent;
2. агент прекращает дальнейшее чтение почты в рамках текущего запуска job;
3. при следующем запуске job письмо со статусом `ERROR` должно попасть в начало очереди обработки;
4. после успешной доставки в MemoryService статус должен стать успешным/обработанным;
5. пока письмо находится в `ERROR`, MailAgent не должен бесконечно читать новые письма поверх неразобранной ошибки.

Цель — добиться гарантии at-least-once доставки полезных писем в MemoryService без потери задач и заметок.

## Решение

### 1. Добавить статус обработки письма

Ввести явный статус обработки для письма в MailAgent:

```text
NEW / PROCESSING / PROCESSED / ERROR
```

Минимально допустимый вариант — расширить текущую таблицу `mailagent.processed_emails` так, чтобы она отражала не только факт дедупликации, но и состояние обработки.

Рекомендуемая модель:

```text
mailagent.processed_emails
- id
- message_id
- subject
- sender
- response_type
- status
- error_message
- attempts_count
- first_seen_at
- last_attempt_at
- processed_at
- created_at
- updated_at
```

### 2. Изменить порядок обработки job

При каждом запуске job обработки почты порядок должен быть таким:

```text
1. Найти письма со статусом ERROR.
2. Если ERROR есть — обработать их первыми, по last_attempt_at ASC / created_at ASC.
3. Только если ERROR очередь пустая — читать новые письма из почтового ящика.
4. Если при обработке любого письма произошла ошибка доставки в MemoryService — поставить ERROR и остановить текущий job-run.
```

Важно: `ERROR` письма должны быть в начале очереди следующего запуска, а не смешиваться с новыми письмами.

### 3. Поведение при ошибке MemoryService

Ошибка доставки в MemoryService — это любое из условий:

- HTTP 5xx от MemoryService;
- HTTP 4xx, если тело/контракт не принято;
- timeout;
- connection refused;
- exception клиента;
- некорректный ответ, если клиент ожидает `2xx`.

При такой ошибке MailAgent должен:

1. сохранить/обновить запись письма в БД;
2. поставить `status = ERROR`;
3. увеличить `attempts_count`;
4. записать `error_message` и `last_attempt_at`;
5. не помечать письмо прочитанным на почтовом сервере;
6. не перемещать письмо в `processed/`;
7. остановить дальнейшую обработку текущего batch/job-run.

### 4. Поведение при успешном retry

Если письмо из `ERROR` успешно доставлено в MemoryService:

- `status = PROCESSED`;
- `processed_at = now()`;
- `error_message = null` или сохранить последнюю ошибку отдельно;
- письмо больше не попадает в retry-очередь;
- job может продолжить обработку следующих `ERROR`, а затем новых писем.

### 5. Идемпотентность

Так как retry может повторно отправить запрос в MemoryService, нужно исключить дубли задач/заметок.

Требование:

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

Добавить методы репозитория:

```text
findByStatusOrderByLastAttemptAtAscCreatedAtAsc(ERROR)
markProcessing(messageId)
markProcessed(messageId, responseType)
markError(messageId, responseType, errorMessage)
existsProcessed(messageId)
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
  ADD COLUMN IF NOT EXISTS error_message TEXT,
  ADD COLUMN IF NOT EXISTS attempts_count INT NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS first_seen_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP,
  ADD COLUMN IF NOT EXISTS processed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_processed_emails_status_attempt
  ON mailagent.processed_emails(status, last_attempt_at, created_at);
```

Если текущая структура `processed_emails` не подходит для статусов, разрешается создать новую таблицу:

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
- `PollScheduler` / mail job должен уметь останавливать текущий batch после ошибки.

## Как тестировать

### Unit tests

1. `MemoryServiceClient`:
   - при `2xx` возвращает success;
   - при `5xx`, timeout, connection refused бросает exception.

2. `ActionExecutor`:
   - при ошибке MemoryService ставит `ERROR`;
   - не помечает письмо processed;
   - увеличивает `attempts_count`.

3. `MailProcessingJob`:
   - сначала выбирает `ERROR` письма;
   - не читает новые письма, пока есть `ERROR`;
   - останавливает batch после новой ошибки MemoryService.

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
   - `attempts_count = 1`;
   - `error_message` заполнен.
6. Проверить, что письмо не помечено read/processed на почтовом сервере.
7. Отправить второе новое письмо.
8. Запустить JavaMemoryService.
9. Запустить следующий poll/job.
10. Проверить, что первым обработано письмо из `ERROR`.
11. Проверить, что задача появилась в MemoryService.
12. Проверить, что `status = PROCESSED`.
13. Проверить, что только после этого обработано второе письмо.

### Acceptance Criteria

- Если MemoryService недоступен при сохранении результата письма, MailAgent сохраняет письмо со статусом `ERROR`.
- После `ERROR` MailAgent прекращает дальнейшее чтение/обработку новых писем в текущем запуске job.
- При следующем запуске job письма со статусом `ERROR` обрабатываются первыми.
- Успешный retry переводит письмо из `ERROR` в `PROCESSED`.
- Повторная доставка не создаёт дубль задачи/заметки в MemoryService.
- Для `NOISE` логика не ломается: письмо может быть помечено прочитанным только после успешного завершения действия.
- Для `DRAFT` логика не ломается: черновик не должен создаваться дублем при retry.
- Добавлен E2E сценарий `08_memory_delivery_error_retry.md`.
- Обновлена документация по JavaMailAgent flow в `ARCHITECTURE.md` или RFC MailAgent.

## Риски / Вопросы

1. Нужно проверить текущую семантику `processed_emails`: если таблица сейчас означает только дедупликацию, расширение до state-machine может потребовать аккуратной миграции.
2. Нужно решить, считать ли HTTP `409 Conflict` от MemoryService успешным результатом retry, если объект уже создан ранее.
3. Для `DRAFT` нужен отдельный guard от дублей черновиков, если ошибка случится после записи файла, но до финального статуса.

## Инструкция для локального агента

1. Прочитать этот CR.
2. Найти текущий flow обработки писем в JavaMailAgent.
3. Найти текущую структуру `processed_emails` и миграции Flyway.
4. Реализовать state-machine обработки писем.
5. Добавить retry-first очередь для `ERROR`.
6. Обеспечить остановку batch/job-run после ошибки доставки в MemoryService.
7. Проверить идемпотентность MemoryService по `emailId/sourceId`.
8. Добавить unit и E2E тесты.
9. Обновить документацию.
10. Прогнать тесты JavaMailAgent и интеграционный сценарий email → MemoryService.
