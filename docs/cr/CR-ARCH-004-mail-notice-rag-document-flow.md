# CR-ARCH-004: Mail NOTICE как RAG-документ

**Дата:** 2026-06-15  
**Статус:** Draft  
**Сервис:** ARCH / MAIL / MEM / RAG  
**Зависимости:** JavaMailAgent, JavaMemoryService, JavaRagService, UI Notice

---

## Проблема / Мотивация

Текущий flow обработки заметок/notice из почты содержит избыточную и потенциально противоречивую логику.

Сейчас часть данных может попадать в отдельные таблицы Memory Service как статические заметки, а часть — в файловую шину RAG. Это создаёт несколько источников истины:

- `memory.notes` / отдельная notice-таблица;
- файлы в `rag-inbox/`;
- статус индексации в `rag.indexed_documents`;
- UI, который может отображать данные не из того источника, который реально используется RAG.

Для knowledge-сценария `NOTICE` должен быть не отдельной бизнес-сущностью Memory Service, а документом базы знаний, который проходит единый RAG lifecycle: файл → валидация → индексация → статус → редактирование → переиндексация.

---

## Архитектурное решение

Ввести отдельный mail classification type:

```text
NOTICE
```

`NOTICE` означает: письмо содержит полезную информацию для базы знаний техлида, но не является задачей, черновиком ответа или шумом.

Целевой поток:

```text
Email
  ↓
JavaMailAgent
  ↓ classify
REQUEST | DRAFT | NOISE | CAPTURE | NOTICE
  ↓
NOTICE
  ↓
Generate Markdown document in RAG format
  ↓
JavaRagService/rag-inbox/mail/YYYY-MM-DD/{message-id}.md
  ↓
JavaRagService scheduler
  ↓
validate → index → update rag.indexed_documents
  ↓
UI /notice reads indexed documents via Memory-owned proxy or RAG REST API
  ↓
User edits document
  ↓
status = outdated / reindex_required
  ↓
RAG scheduler reindexes
```

---

## Принципы

1. **RAG-файл является source of truth для NOTICE.**
   Notice не должен дублироваться как статическая запись в отдельной таблице.

2. **Memory Service не владеет RAG-документами.**
   Memory может давать UI/proxy-слой, но не должен хранить вторую копию notice-содержимого.

3. **JavaRagService владеет lifecycle индексации.**
   Статусы `indexed`, `invalid`, `failed`, `outdated` остаются в `rag.indexed_documents`.

4. **UI Notice показывает не заметки из Memory, а документы, которые реально видит RAG.**

5. **Редактирование notice должно приводить к переиндексации, а не к расхождению UI и RAG.**

---

## Изменения в JavaMailAgent

### 1. Добавить тип классификации

Расширить enum `AgentResponseType`:

```java
REQUEST,
DRAFT,
NOISE,
CAPTURE,
NOTICE
```

### 2. Обновить prompt классификации письма

Агент должен отличать:

| Тип | Значение |
|-----|----------|
| `REQUEST` | Нужно действие / задача / дедлайн |
| `DRAFT` | Нужно подготовить ответ |
| `NOISE` | Уведомление без пользы |
| `CAPTURE` | Сырая заметка для Memory Capture Bot |
| `NOTICE` | Полезная knowledge-информация для RAG |

### 3. Добавить NoticeDocumentWriter

Для `NOTICE` MailAgent должен генерировать Markdown-файл в RAG-compatible формате.

Целевой путь:

```text
JavaRagService/rag-inbox/mail/YYYY-MM-DD/{safe-message-id}.md
```

Минимальный формат файла:

```markdown
---
type: NOTICE
source: mail
updated: YYYY-MM-DD
message_id: <mail message id>
sender: <sender>
subject: <subject>
received_at: <timestamp>
review_by: YYYY-MM-DD
---

# <subject>

## Контекст

Краткое описание письма и почему оно важно.

## Содержание

Нормализованное содержание письма без мусора, подписей и технических заголовков.

## Возможное применение

Как эта информация может помочь техлиду: процессы, договорённости, архитектура, риски, команда.
```

### 4. Обновить обработку read/unread

`NOTICE` после успешного создания файла считается обработанным в `processed_emails`.

Решение по read-status:

- `REQUEST` и `DRAFT` остаются unread;
- `NOISE` mark as read;
- `NOTICE` можно mark as read только после успешной записи файла;
- `CAPTURE` — по текущему правилу Capture flow.

---

## Изменения в JavaRagService

### 1. Поддержать DocType NOTICE

Расширить `DocType`:

```java
NOTICE
```

### 2. Добавить DocSchema для NOTICE

Минимальные обязательные поля frontmatter:

```text
type
source
updated
review_by
```

Желательные поля:

```text
message_id
sender
subject
received_at
```

Обязательные секции:

```text
## Контекст
## Содержание
## Возможное применение
```

### 3. Статус outdated / reindex_required

Если UI редактирует файл, RAG должен уметь понять, что документ изменился и требует переиндексации.

Минимальный MVP:

- scheduler считает hash файла;
- если hash отличается от `indexed_documents.file_hash`, документ переиндексируется;
- при необходимости статус может временно выставляться в `outdated`.

---

## Изменения в JavaMemoryService / UI

### 1. UI `/ui/notice`

Добавить страницу Notice Knowledge Inbox.

Она должна показывать список RAG-документов типа `NOTICE`, а не отдельную таблицу Memory.

Колонки:

| Поле | Источник |
|------|----------|
| Title / subject | frontmatter или первый h1 |
| Sender | frontmatter.sender |
| Received at | frontmatter.received_at |
| Status | `rag.indexed_documents.status` |
| Error | `error_message` |
| Updated | `updated` / indexed_at |
| Actions | view / edit / reindex |

### 2. API для UI

Предпочтительный вариант: Memory-owned proxy, чтобы UI не ходил напрямую в RAG.

Новые endpoints в Memory Service:

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/notices` | список RAG NOTICE-документов |
| `GET` | `/api/notices/{id}` | открыть документ |
| `PUT` | `/api/notices/{id}` | сохранить изменённый Markdown |
| `POST` | `/api/notices/{id}/reindex` | запросить переиндексацию |

Внутри Memory Service эти endpoints могут делегировать в JavaRagService REST API.

### 3. Не создавать отдельную notice-таблицу в Memory

Разрешается хранить только технические UI-настройки или audit events, но не копию содержимого notice.

---

## Изменения в API JavaRagService

Если текущего API недостаточно для UI, добавить:

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/rag/documents?type=NOTICE` | список документов по типу |
| `GET` | `/api/rag/documents/{id}` | метаданные + markdown content |
| `PUT` | `/api/rag/documents/{id}` | обновить markdown-файл |
| `POST` | `/api/rag/documents/{id}/reindex` | переиндексировать один документ |

MVP может использовать existing `/api/rag/status` + file path, если этого достаточно, но целевой контракт лучше сделать явным.

---

## Изменения в схеме БД

### JavaMailAgent

Если `processed_emails.agent_type` ограничен enum/validation, добавить `NOTICE`.

Дополнительно можно добавить поля:

```sql
ALTER TABLE mailagent.processed_emails
    ADD COLUMN IF NOT EXISTS output_path TEXT;
```

`output_path` хранит путь созданного RAG-файла для NOTICE.

### JavaRagService

Если в `indexed_documents` нет doc_type, добавить:

```sql
ALTER TABLE indexed_documents
    ADD COLUMN IF NOT EXISTS doc_type VARCHAR(50);
```

Это позволит эффективно фильтровать `/api/rag/documents?type=NOTICE` без чтения каждого файла.

### JavaMemoryService

Не добавлять таблицу `notice` для хранения содержимого.

Допустимо добавить usage/audit event:

```text
NOTICE_CREATED
NOTICE_EDITED
NOTICE_REINDEX_REQUESTED
NOTICE_INDEXED
```

---

## Зависимости от других CR

Желательно выполнить после:

1. `CR-RAG-BUGFIX-002-rest-api.md` — нужен REST API RAG.
2. `CR-RAG-002-document-validation.md` — нужен валидатор документов.
3. `CR-002-processed-emails-tracking.md` — нужен processed email tracking.

---

## E2E сценарии

Добавить интеграционный сценарий:

```text
e2e-integration/07_mail_notice_to_rag_document.md
```

Проверка:

1. Отправить письмо в Maildev с маркером `NOTICE`.
2. Запустить poll JavaMailAgent.
3. Проверить, что создан файл в `JavaRagService/rag-inbox/mail/YYYY-MM-DD/`.
4. Проверить, что файл содержит валидный frontmatter `type: NOTICE`.
5. Дождаться RAG scheduler.
6. Проверить `/api/rag/status`, что документ `indexed`.
7. Выполнить `/api/search` по фразе из письма.
8. Проверить, что результат возвращает source созданного NOTICE-файла.
9. Открыть `/ui/notice` и проверить, что документ виден в списке.
10. Изменить документ через UI/API.
11. Проверить, что после изменения hash обновился и документ переиндексирован.

---

## Как тестировать вручную

```bash
# 1. Поднять инфраструктуру
./test-runner/build.sh
./test-runner/start-services.sh --profile local
./test-runner/healthcheck.sh

# 2. Отправить NOTICE письмо в Maildev
curl -s --url "smtp://localhost:1025" \
  --mail-from "architect@example.com" \
  --mail-rcpt "me@example.com" \
  --upload-file - <<EOF
Subject: NOTICE: Новый порядок релизов
From: architect@example.com
To: me@example.com

NOTICE
С сегодняшнего дня релизы backend-сервисов согласуются через общий release calendar.
Перед выкладкой нужно проверить зависимости и заполнить release notes.
EOF

# 3. Запустить poll MailAgent или дождаться scheduler

# 4. Проверить файл
find JavaRagService/rag-inbox/mail -type f -name "*.md"

# 5. Проверить статус RAG
curl http://localhost:8081/api/rag/status

# 6. Проверить поиск
curl -s -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"новый порядок релизов release calendar", "top_k": 5}'

# 7. Проверить UI
open http://localhost:8082/ui/notice
```

---

## Критерии готовности

- MailAgent умеет классифицировать письмо как `NOTICE`.
- Для `NOTICE` создаётся валидный Markdown-документ RAG-формата.
- Документ попадает в `rag-inbox/mail/...`.
- RAG валидирует и индексирует документ.
- `/api/rag/status` или новый `/api/rag/documents?type=NOTICE` показывает документ и статус.
- `/ui/notice` показывает список NOTICE-документов из RAG lifecycle.
- Редактирование документа приводит к переиндексации.
- Не создана отдельная таблица со статическим содержимым notice в Memory Service.
- E2E сценарий `07_mail_notice_to_rag_document.md` проходит.

---

## Ожидаемый результат

После реализации flow становится единым и непротиворечивым:

```text
Mail NOTICE = RAG document
UI Notice = окно управления RAG-документами
RAG status = источник правды об индексации
Memory = gateway/UI/orchestration, но не duplicate storage
```
