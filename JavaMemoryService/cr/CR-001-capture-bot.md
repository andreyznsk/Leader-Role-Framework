# CR-001: Capture Bot — захват заметок в течение дня

**Дата:** 2026-06-09  
**Статус:** Draft  
**Сервис:** JavaMemoryService  
**Зависимости:** PostgreSQL, JavaRagService (rag-inbox/)

---

## Проблема / Мотивация

Техлид генерирует поток наблюдений, рисков, решений в течение дня — в движении,
на встречах, между задачами. Нужен канал захвата с минимальным трением:
написал → попало в inbox → вечером агент разобрал всё пачкой.

Классификация синхронно при capture не нужна: добавляет latency, прерывает поток,
увеличивает стоимость. Правильнее — накопить за день, разобрать один раз.

---

## Решение

### Поток данных

```
capture "текст заметки"
        ↓
POST /api/capture  →  таблица captures (raw, status=PENDING)
                       + файл в capture-inbox/YYYY-MM-DD/

[конец дня — триггер вручную или по расписанию 18:00]
        ↓
POST /api/capture/process-today
        ↓
CaptureClassifierAgent (claude --print, пачка за день)
        ↓
CaptureRouter → PostgreSQL / rag-inbox/ / journal
```

### Компоненты

#### 1. `POST /api/capture` — приём заметки

Сохраняет raw текст. Никакой классификации, никакого claude.
Быстро, всегда доступно.

```json
// request
{ "text": "Иван не знает rollback — нужно провести сессию", "source": "cli" }

// response
{ "captureId": "uuid", "savedAt": "2026-06-09T14:32:00" }
```

#### 2. `capture-inbox/` — файловый буфер

Параллельно с БД — каждая заметка пишется в файл:
```
capture-inbox/
└── 2026-06-09/
    ├── 14-32-00-abc.md
    ├── 15-10-22-def.md
    └── ...
```

Формат файла:
```markdown
# Capture 2026-06-09 14:32:00
source: cli
---
Иван не знает rollback — нужно провести сессию
```

Папка `capture-inbox/` — аналог `rag-inbox/`. Агент читает её при обработке.

#### 3. `POST /api/capture/process-today` — запуск обработки

Триггер — вручную или по cron (18:00 по расписанию через `ScheduledExecutorService`).

Собирает все `PENDING` captures за сегодня, формирует один батч-промпт,
запускает `claude --print`.

**Промпт агенту:**
```
Ты — ассистент техлида. Разбери заметки за день.
Для каждой заметки верни JSON-объект в массиве:
{
  "captureId": "...",
  "type": "TASK|RISK|PERSON_NOTE|KNOWLEDGE|JOURNAL|QUESTION",
  "title": "...",
  "body": "...",
  "priority": "LOW|NORMAL|HIGH|CRITICAL"
}
Заметки:
[список с captureId и текстом]
```

#### 4. `CaptureRouter` — маршрутизация результатов

| type | куда |
|------|------|
| `TASK` | `POST /api/tasks/pending` (PENDING queue, юзер подтверждает) |
| `RISK` | `POST /api/risks` |
| `QUESTION` | `POST /api/questions` |
| `PERSON_NOTE` | `POST /api/people/{name}/notes` |
| `KNOWLEDGE` | файл в `rag-inbox/captures/` → JavaRagService подхватит |
| `JOURNAL` | аппенд в `workspace/08_daily_journal/YYYY-MM-DD.md` |

После маршрутизации: `captures.status = PROCESSED`.

#### 5. CLI алиас (не в JAR)

```bash
# ~/.zshrc
capture() {
  curl -s -X POST http://localhost:8082/api/capture \
    -H "Content-Type: application/json" \
    -d "{\"text\": \"$*\", \"source\": \"cli\"}" | jq -r '.captureId'
}
```

---

## Изменения в API (JavaMemoryService)

| Метод | Путь | Описание |
|-------|------|----------|
| `POST` | `/api/capture` | Принять заметку (raw, без классификации) |
| `GET` | `/api/capture/today` | Все captures за сегодня |
| `POST` | `/api/capture/process-today` | Запустить агент-классификатор на сегодняшний inbox |
| `GET` | `/api/capture/recent` | Последние 20 (для UI) |
| `POST` | `/api/risks` | Сохранить риск |
| `POST` | `/api/questions` | Сохранить открытый вопрос |
| `POST` | `/api/people/{name}/notes` | Заметка о человеке |

---

## Изменения в схеме БД

```sql
CREATE TABLE captures (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_text    TEXT NOT NULL,
    source      VARCHAR(50) NOT NULL DEFAULT 'cli',
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | PROCESSED | SKIPPED
    classified  VARCHAR(20),   -- заполняется после обработки
    routed_to   VARCHAR(100),  -- куда отправлено
    captured_at TIMESTAMP NOT NULL DEFAULT now(),
    processed_at TIMESTAMP
);

CREATE TABLE risks (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(255) NOT NULL,
    description TEXT,
    priority    VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE questions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       VARCHAR(255) NOT NULL,
    context     TEXT,
    status      VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE person_notes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    person_name VARCHAR(100) NOT NULL,
    note        TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now()
);
```

Flyway: `V5__add_capture_tables.sql`

---

## Расписание (ScheduledExecutorService в JavaMemoryService)

Добавить в существующий планировщик (рядом с briefing scheduler):

```
18:00 — автозапуск process-today если есть PENDING captures
```

Или вручную: `curl -X POST http://localhost:8082/api/capture/process-today`

---

## Зависимости от других сервисов

- **JavaRagService** — `rag-inbox/captures/` для KNOWLEDGE (авто-подхват через существующий watcher)
- **claude --print** — батч-классификация в конце дня
- **JavaMailAgent** — не зависит

---

## Фазы реализации

**Фаза 1 — MVP (text capture + батч-обработка)**
- `POST /api/capture`, `capture-inbox/`, таблица captures
- `POST /api/capture/process-today` + CaptureClassifierAgent
- CaptureRouter (TASK, RISK, KNOWLEDGE, JOURNAL)
- CLI алиас

**Фаза 2 — UI**
- Страница `/ui/capture` — список за день, кнопка "Обработать"
- Возможность вручную переклассифицировать заметку

**Фаза 3 — Messenger Adapter**
- Адаптер корпоративного мессенджера → `POST /api/capture`

---

## Как тестировать

```bash
# 1. Накидать заметок в течение дня
capture "Иван не знает rollback — провести сессию"
capture "Риск: только один человек знает деплой в prod"
capture "Надо разобраться как устроен service-mesh у нас"
capture "Встреча с Петром — он хочет перейти в другую команду"

# 2. Проверить inbox
curl http://localhost:8082/api/capture/today

# 3. Запустить обработку
curl -X POST http://localhost:8082/api/capture/process-today

# 4. Проверить результаты
curl http://localhost:8082/api/tasks/today        # TASK
curl http://localhost:8082/api/risks              # RISK
ls rag-inbox/captures/                            # KNOWLEDGE
cat workspace/08_daily_journal/2026-06-09.md      # JOURNAL
```