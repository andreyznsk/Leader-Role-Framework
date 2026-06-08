# Tech Lead Framework — Копилка идей

> Документ обновляется по ходу чата. Последнее обновление: 2026-06-07

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

**Инфраструктура (Docker):**
- PostgreSQL
- OpenSearch + OpenSearch Dashboards
- Ollama (multilingual-e5-large, Metal на M1)

**Логика (локально, jar):**
- java-rag-service.jar
- java-memory-service.jar

**Принцип:**
- Java пишет (парсинг Jira, почты, Excel) → PostgreSQL / OpenSearch
- Агент только читает через MCP java-сервисы

---

## Идея 1 — Structured Memory (PostgreSQL)

**Стек:** PostgreSQL в Docker + java-memory-service.jar + Claude Agent

Оперативные данные агента хранятся структурированно:
- ежедневные планы и результаты дня
- входящая почта и рабочие переписки
- инциденты, риски, решения
- динамические заметки по людям

Агент при старте сессии загружает актуальный контекст через /context.

---

## Идея 2 — RAG по документации (OpenSearch)

**Стек:** OpenSearch в Docker + java-rag-service.jar + Ollama + Claude Agent

Документы индексируются в OpenSearch, агент делает семантический поиск перед ответом.

Что индексируется:
- архитектурные документы (ADR, C4, runbooks)
- процессы команды (release flow, onboarding)
- Markdown-артефакты из workspace

**Embedding модель:** Ollama + `multilingual-e5-large`
- Локально, бесплатно, Metal acceleration на M1 (~2GB RAM)
- Отличное качество для русскоязычных документов
- Индексация один раз, поиск дёшево по токенам

**Плохо подходит для RAG:** таблицы, Excel, графики, часто меняющиеся данные → всё это в PostgreSQL.

---

## Идея 3 — Capacity Visualization

**Стек:** Jira REST API → java-memory-service.jar → PostgreSQL → Grafana

Визуализация загрузки команды в разных разрезах:
- capacity по разработчику на спринт
- загрузка по эпикам
- план vs факт по спринту
- тренд velocity по кварталам
- свободные руки на следующий спринт

Основная таблица:
```sql
sprint_capacity(developer, sprint, epic, planned_points, actual_points, date)
```

Синхронизация раз в день через Java job.

---

## Идеи в очереди (не согласованы)

- Weekly Dashboard в Grafana по артефактам агента
- Java-агент для парсинга Excel от смежников
- Мониторинг почты → Daily Brief через JavaMail

---

## Идея 4 — Notification Hooks (macOS)

**Стек:** Claude Code hooks + osascript + say

Хуки на события агента — не нужно смотреть в терминал:

```bash
# Задача завершена
say "Клод завершил задачу"
osascript -e 'display notification "Задача выполнена" with title "Claude Code" sound name "Glass"'

# Требуется подтверждение пользователя
say "Клод ждёт твоего ответа"
osascript -e 'display notification "Требуется подтверждение" with title "Claude Code" sound name "Ping"'
```

**События для хуков:**
- `Stop` → агент завершил задачу
- `PreToolUse` → агент запрашивает подтверждение перед действием

**Сценарий:** запустил долгую задачу (индексация, парсинг Jira) → пошёл по делам → Mac голосом сообщил о завершении или что нужен ответ.

---

## Идея 5 — Проактивный Briefing (зависит от идей 1, 2, 4)

**Стек:** java-memory-service.jar (scheduler) + java-rag-service + Claude API + osascript/say

За 1 час до события из daily plan агент собирает контекст и уведомляет голосом.

**Как работает:**

```
java-memory-service.jar (ScheduledExecutorService, каждые 15 мин)
    └── SELECT события из PostgreSQL на ближайший час
            └── триггер briefing:
                    ├── java-rag-service /search → контекст из OpenSearch
                    ├── java-memory-service /context → история из PostgreSQL
                    └── Claude API → генерирует briefing
                            └── say + osascript → уведомление
```

**Формат briefing:**
- что за событие и когда
- релевантная документация из RAG
- история по теме из PostgreSQL (инциденты, решения)
- что нужно сделать до события

**Порядок реализации:** после идей 1 → 2 → 4
