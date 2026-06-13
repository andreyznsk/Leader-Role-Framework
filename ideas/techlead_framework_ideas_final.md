# Tech Lead Framework — Копилка идей (актуальная версия)

> Последнее обновление: 2026-06-13

---

## Финальная архитектура

```
Claude Agent
    ├── MCP java-rag-service → java -jar rag-service.jar (порт 8081)
    │       ├── /search → Ollama + OpenSearch (RAG)
    │       └── /index  → индексация документов
    │
    └── MCP java-memory-service → java -jar memory-service.jar (порт 8082)
            ├── /context → SELECT контекст сессии из PostgreSQL
            └── /write   → INSERT оперативных данных
```

Инфраструктура (Docker): PostgreSQL, OpenSearch + Dashboards, Ollama (multilingual-e5-large, M1)
Логика (локально, jar): java-rag-service.jar, java-memory-service.jar
Принцип: Java пишет → агент только читает через MCP

---

## Идея 1 — Structured Memory (PostgreSQL)
PostgreSQL + java-memory-service. Оперативные данные: планы, почта, инциденты, риски, люди.
Задача в PostgreSQL (id, title, priority, status) + файл workspace/tasks/TASK-{id}.md (описание, история).

## Идея 2 — RAG по документации (OpenSearch)
OpenSearch + java-rag-service + Ollama (multilingual-e5-large, Metal M1).
Индексация: ADR, runbooks, процессы, Markdown. Не подходит: таблицы, Excel, часто меняющиеся данные.

## Идея 3 — Capacity Visualization
Jira REST API → java-memory-service → PostgreSQL → Grafana.
sprint_capacity(developer, sprint, epic, planned_points, actual_points, date)

## Идея 4 — Notification Hooks
~/.claude/settings.json: Stop → notify-send/osascript "Задача завершена: $(basename $PWD)"
Notification → "Требуется ответ: $(basename $PWD)" (не спамит при авто-подтверждении)

## Идея 5 — Проактивный Briefing (после 1, 2, 4)
ScheduledExecutorService каждые 15 мин → за час до события → RAG + PostgreSQL → Claude API → briefing → notify-send.

## Идея 6 — Capture Bot (систематизация хаоса)
Модуль в java-memory-service. Capture в течение дня → capture-inbox/YYYY-MM-DD/HH-MM-SS.md.
Конец дня (18:00): claude --print классифицирует → TASK/RISK/NOTE/QUESTION → PostgreSQL / OpenSearch / journal.
NOTE → таблица notes(id, text, tags, source, created_at) + страница /ui/notes.

## Идея 8 — Weekly Routine Manager (гипотеза, после 1, 2, 5)
Модуль в java-memory-service. UI /ui/routines + /ui/routines/{id}/result.
routines(id, name, cron, prompt, materials, active). Кнопка [▶] — запустить вручную.
GET /api/calendar/today в java-mail-agent → встречи из EWS.
Результат агента отображается в UI, не в notify-send.

## Идея 9 — End of Day Summary (после 1, 2, 6)
Scheduler 18:00: git diff HEAD~1 + capture-inbox → Claude API → резюме дня → PostgreSQL + OpenSearch + git commit "EOD: YYYY-MM-DD".

---

## LeaderOS Daily Cycle (концепция)
06:00 Подготовка дня → git pull + Jira sync + daily plan
08:00 Утренний briefing → EWS календарь + рутины + превью встреч
День  Capture Bot → принимает заметки
-1ч   Проактивный briefing → контекст перед встречей
18:00 Закрытие дня → git diff + EOD summary + коммит
Пт    Недельный итог → velocity + open risks

---

## CR Workflow

Префиксы: MEM | RAG | MAIL | CLAUDE | ARCH
Формат файла: CR-{PREFIX}-{NNN}-{название}.md
Папки: корень/cr/ (ARCH+CLAUDE), JavaMemoryService/cr/, JavaRagService/cr/, JavaMailAgent/cr/

Формат коммита: {PREFIX}_{тип}_{номер} {описание}
Типы: cr | bugfix | manual | eod

---

## Агент-агностик подход
AGENT.md — базовый контракт (роль, workspace, CR workflow, capture)
CLAUDE.md — Claude-специфичное (MCP серверы, handoff, Drive/Calendar ids)
Другие агенты: свой файл расширяет AGENT.md
