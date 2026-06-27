# CR-ARCH-005: Universal Knowledge UI поверх RAG-документов

**Дата:** 2026-06-15  
**Статус:** Draft  
**Сервис:** ARCH / MEM / RAG / UI  
**Зависимости:** CR-ARCH-004, CR-RAG-BUGFIX-002, CR-RAG-002  

---

## Проблема / Мотивация

После обсуждения flow `Mail NOTICE → RAG document` стало понятно, что отдельная страница `/ui/notice` решает только частный случай.

Но архитектурно `NOTICE` — это не самостоятельная сущность, а один из типов документов базы знаний.

Сегодня в RAG уже есть или планируются типы:

- `NOTICE` — knowledge из почты;
- `ADR` — архитектурные решения;
- `SERVICE_CARD` — карточки сервисов;
- `PROCESS` — процессы команды;
- `GLOSSARY` — глоссарий;
- future: `MEETING_NOTE`, `CAPTURE_KNOWLEDGE`, `RUNBOOK`, `INCIDENT_REVIEW`.

Если делать отдельный UI под каждый тип, появится дублирование:

- `/ui/notice`;
- `/ui/adr`;
- `/ui/processes`;
- `/ui/service-cards`;
- отдельные API и похожие таблицы.

Правильнее сделать единый UI управления знаниями, где `NOTICE` — просто фильтр по типу документа.

---

## Архитектурное решение

Создать универсальную страницу:

```text
/ui/knowledge
```

Она отображает все документы, которые находятся в lifecycle JavaRagService:

```text
markdown file → validation → indexed_documents → OpenSearch chunks → search
```

`/ui/notice` может быть алиасом/предустановленным фильтром:

```text
/ui/knowledge?type=NOTICE
```

---

## Границы ответственности

### JavaRagService

Владеет:

- файлами в `rag-inbox/`;
- чтением Markdown content;
- YAML frontmatter;
- валидацией структуры;
- таблицей `rag.indexed_documents`;
- статусами индексации;
- OpenSearch chunks;
- переиндексацией.

### JavaMemoryService

Владеет:

- UI;
- web routes `/ui/knowledge`;
- proxy API для браузера;
- навигацией LeaderOS;
- usage/audit events;
- orchestration, но не содержимым RAG-документов.

Memory Service **не ходит напрямую в схему `rag`** и не читает `rag.indexed_documents` через JDBC.

Все обращения идут только через JavaRagService REST API:

```text
Browser → JavaMemoryService → JavaRagService → rag schema/files/OpenSearch
```

---

## Целевой flow

```text
User opens /ui/knowledge
  ↓
JavaMemoryService Controller
  ↓
RagKnowledgeClient HTTP
  ↓
GET JavaRagService /api/rag/documents?type=...
  ↓
JavaRagService reads indexed_documents + markdown frontmatter
  ↓
DTO back to Memory
  ↓
Thymeleaf renders table
```

Открытие документа:

```text
User clicks document
  ↓
GET /ui/knowledge/{id}
  ↓
Memory → GET /api/rag/documents/{id}
  ↓
RAG returns metadata + markdown + index status
  ↓
UI renders preview/editor
```

Редактирование:

```text
User edits markdown
  ↓
PUT /api/knowledge/documents/{id}
  ↓
Memory proxy
  ↓
PUT /api/rag/documents/{id}
  ↓
RAG updates file
  ↓
status = outdated or hash changed
  ↓
RAG scheduler / manual reindex
```

---

## UI: `/ui/knowledge`

### Название страницы

```text
Knowledge Base
```

или

```text
RAG Knowledge Inbox
```

### Основные элементы

```text
[ Knowledge Base ]

Search: [____________________]

Types:
[ ALL ] [ NOTICE ] [ ADR ] [ SERVICE_CARD ] [ PROCESS ] [ GLOSSARY ] [ INVALID ]

Statuses:
[ indexed ] [ invalid ] [ failed ] [ outdated ]

Table:
| Status | Type | Title | Source | Updated | Chunks | Error | Actions |
|--------|------|-------|--------|---------|--------|-------|---------|
| indexed | NOTICE | Новый порядок релизов | mail | 2026-06-15 | 4 | — | View Edit Reindex |
| invalid | SERVICE_CARD | КСК service | confluence | 2026-06-14 | 0 | Нет ## Деплой | Edit |
```

### Колонки таблицы

| Колонка | Источник |
|---------|----------|
| `Status` | `indexed_documents.status` |
| `Type` | `doc_type` или frontmatter `type` |
| `Title` | frontmatter `title` или первый `# H1` |
| `Source` | frontmatter `source` |
| `Updated` | frontmatter `updated` или `indexed_at` |
| `Chunks` | `indexed_documents.chunk_count` |
| `Error` | `indexed_documents.error_message` |
| `Actions` | view / edit / reindex / search-test |

---

## UI: карточка документа

URL:

```text
/ui/knowledge/{id}
```

Экран:

```text
[ NOTICE ] Новый порядок релизов

Status: indexed
Source: mail
File: rag-inbox/mail/2026-06-15/mail-001.md
Updated: 2026-06-15
Indexed at: 2026-06-15 10:31
Chunks: 4
Hash: abc123...

Actions:
[ Edit ] [ Reindex ] [ Validate ] [ Test Search ]

Tabs:
[ Preview ] [ Markdown ] [ Metadata ] [ Index Status ] [ Search Test ]
```

### Preview tab

Рендерит Markdown в читаемом виде.

### Markdown tab

Показывает raw Markdown.

### Metadata tab

Показывает frontmatter.

### Index Status tab

Показывает статус индексации, ошибку, chunk count, last indexed.

### Search Test tab

Позволяет выполнить search query и проверить, находится ли документ в результатах.

---

## Memory Service API

Memory Service даёт browser-facing proxy API.

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/knowledge/documents` | список документов с фильтрами |
| `GET` | `/api/knowledge/documents/{id}` | документ целиком |
| `PUT` | `/api/knowledge/documents/{id}` | обновить Markdown |
| `POST` | `/api/knowledge/documents/{id}/reindex` | запросить reindex |
| `POST` | `/api/knowledge/documents/{id}/validate` | проверить документ без индексации |
| `POST` | `/api/knowledge/search-test` | выполнить тестовый поиск |

Memory Service реализует эти endpoints как HTTP-клиент к JavaRagService.

Класс:

```text
RagKnowledgeClient
```

Конфигурация:

```yaml
rag:
  base-url: http://localhost:8081
```

---

## JavaRagService API

Добавить document-management REST API.

| Метод | Путь | Описание |
|-------|------|----------|
| `GET` | `/api/rag/documents` | список документов |
| `GET` | `/api/rag/documents/{id}` | документ + markdown content |
| `PUT` | `/api/rag/documents/{id}` | обновить markdown file |
| `POST` | `/api/rag/documents/{id}/reindex` | переиндексировать документ |
| `POST` | `/api/rag/documents/{id}/validate` | провалидировать документ |

### Query params для списка

```text
type=NOTICE|ADR|SERVICE_CARD|PROCESS|GLOSSARY
status=indexed|invalid|failed|outdated
source=mail|confluence|manual|capture
q=<text>
limit=50
offset=0
```

---

## DTO: список документов

```json
{
  "items": [
    {
      "id": "doc-uuid-or-stable-hash",
      "filePath": "rag-inbox/mail/2026-06-15/mail-001.md",
      "type": "NOTICE",
      "title": "Новый порядок релизов",
      "source": "mail",
      "status": "indexed",
      "updated": "2026-06-15",
      "indexedAt": "2026-06-15T10:31:00",
      "chunkCount": 4,
      "errorMessage": null
    }
  ],
  "total": 1
}
```

---

## DTO: документ

```json
{
  "id": "doc-uuid-or-stable-hash",
  "filePath": "rag-inbox/mail/2026-06-15/mail-001.md",
  "type": "NOTICE",
  "title": "Новый порядок релизов",
  "source": "mail",
  "status": "indexed",
  "metadata": {
    "type": "NOTICE",
    "source": "mail",
    "updated": "2026-06-15",
    "sender": "architect@example.com",
    "subject": "NOTICE: Новый порядок релизов"
  },
  "markdown": "---\ntype: NOTICE\n...",
  "index": {
    "chunkCount": 4,
    "indexedAt": "2026-06-15T10:31:00",
    "errorMessage": null,
    "hash": "abc123"
  }
}
```

---

## Изменения в схеме RAG

Если текущая таблица `indexed_documents` не содержит достаточно метаданных для фильтрации, добавить поля:

```sql
ALTER TABLE indexed_documents
    ADD COLUMN IF NOT EXISTS doc_type VARCHAR(50),
    ADD COLUMN IF NOT EXISTS title TEXT,
    ADD COLUMN IF NOT EXISTS source VARCHAR(100),
    ADD COLUMN IF NOT EXISTS updated DATE;

CREATE INDEX IF NOT EXISTS idx_indexed_documents_doc_type
    ON indexed_documents(doc_type);

CREATE INDEX IF NOT EXISTS idx_indexed_documents_source
    ON indexed_documents(source);
```

Важно: эти поля являются **read model/cache** из frontmatter, а не отдельным источником истины.

Source of truth остаётся Markdown-файл.

---

## Изменения в навигации UI

Добавить пункт:

```text
Knowledge
```

Внутри него фильтры:

```text
All documents
Notices
ADR
Processes
Service Cards
Invalid documents
```

`/ui/notice` допускается как redirect:

```text
/ui/notice → /ui/knowledge?type=NOTICE
```

---

## Usage events

Добавить события в Memory Service:

| Event | Когда |
|-------|-------|
| `KNOWLEDGE_UI_OPENED` | пользователь открыл `/ui/knowledge` |
| `KNOWLEDGE_DOCUMENT_OPENED` | открыт документ |
| `KNOWLEDGE_DOCUMENT_EDITED` | документ изменён |
| `KNOWLEDGE_REINDEX_REQUESTED` | запрошена переиндексация |
| `KNOWLEDGE_SEARCH_TESTED` | выполнен search test из UI |

---

## E2E сценарии

Добавить сценарии:

```text
JavaMemoryService/test_e2e/12_ui_knowledge.md
JavaRagService/test_e2e/07_document_management_api.md
e2e-integration/08_knowledge_ui_notice_document.md
```

### 12_ui_knowledge.md

Проверяет:

1. `/ui/knowledge` отвечает HTTP 200.
2. Есть фильтры по типам.
3. Есть таблица документов.
4. `/ui/notice` делает redirect или показывает NOTICE filter.

### 07_document_management_api.md

Проверяет:

1. Создание тестового Markdown в `rag-inbox/`.
2. Индексацию.
3. `GET /api/rag/documents`.
4. `GET /api/rag/documents/{id}`.
5. `PUT /api/rag/documents/{id}`.
6. `POST /api/rag/documents/{id}/reindex`.

### 08_knowledge_ui_notice_document.md

Проверяет end-to-end:

1. MailAgent создаёт NOTICE-файл.
2. RAG индексирует.
3. Memory `/ui/knowledge?type=NOTICE` показывает документ.
4. Документ открывается.
5. Документ редактируется.
6. Документ переиндексируется.
7. Search возвращает обновлённый текст.

---

## Как тестировать вручную

```bash
# 1. Поднять систему
./test-runner/build.sh
./test-runner/start-services.sh --profile local
./test-runner/healthcheck.sh

# 2. Проверить RAG document API
curl "http://localhost:8081/api/rag/documents?type=NOTICE"

# 3. Проверить Memory proxy
curl "http://localhost:8082/api/knowledge/documents?type=NOTICE"

# 4. Открыть UI
open http://localhost:8082/ui/knowledge
open http://localhost:8082/ui/knowledge?type=NOTICE

# 5. Открыть старый URL notice
open http://localhost:8082/ui/notice
```

---

## Критерии готовности

- `/ui/knowledge` отображает документы из RAG lifecycle.
- `/ui/notice` работает как фильтр `type=NOTICE` или redirect.
- Memory Service не обращается напрямую к схеме `rag`.
- Memory Service общается с JavaRagService только через REST API.
- JavaRagService отдаёт список документов с type/status/source/title.
- JavaRagService отдаёт markdown content документа.
- Можно редактировать документ через UI/API.
- После редактирования документ переиндексируется.
- Можно увидеть invalid/failed документы и ошибку валидации.
- E2E сценарии проходят.

---

## Ожидаемый результат

LeaderOS получает единый интерфейс управления knowledge base:

```text
Knowledge UI = RAG document admin + editor + validation/status dashboard
```

`NOTICE` становится не отдельным экраном и не отдельной таблицей, а обычным типом документа в общей knowledge-модели.

Это снижает дублирование, сохраняет границы сервисов и делает RAG настоящим владельцем знаний.
