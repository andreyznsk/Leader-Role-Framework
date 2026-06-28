# RFC: LeaderOS — Framework тех лида (Presentation)

**Файл:** `JavaMemoryService/src/main/resources/static/presentation.html`  
**Статус:** ACTIVE  
**Автор:** Андрей Зайцев  
**Дата:** 2026

---

## Назначение

Этот документ — RFC для презентации **LeaderOS** трибу / руководству.  
Презентация объясняет: зачем система нужна, что она делает, как устроена и куда движется.

---

## Структура презентации (14 слайдов)

### Слайд 1 — Title

**LeaderOS · Framework тех лида · Второй мозг**

AI-система которая автоматизирует рутину техлида — почту, планирование, документацию, мониторинг команды.

Стек: `Java · Spring Boot 3` · `AI-Agent · RAG` · `k8s-ready` · `работает сейчас`

---

### Слайд 2 — Боль (Часть 1)

**Реальность техлида в большой команде**

| Метрика | Значение |
|---------|----------|
| Часов в неделю на рутину | **3–5** (почта, планирование, поиск) |
| Доля рабочего времени не на инженерию | **~30%** (встречи, согласования) |
| Месяцев онбординга тимлида | **2–3** до уверенной работы |
| Инструментов автоматизации | **0** — каждый изобретает велосипед |

**Ключевой расчёт:**
```
20 тимлидов × 4 ч/нед × 48 недель = 3 840 часов/год
≈ 2 FTE инженеров работающих вхолостую
```

---

### Слайд 3 — Концепция

**LeaderOS — это среда работы. Как IDE для разработчика — только для техлида.**

#### Среда (workspace)
| Компонент | Назначение |
|-----------|-----------|
| `AGENT.md` (агент-агностик) | Один файл конфигурации. Симлинки под каждый агент: `CLAUDE.md · CODEX.md · GIGACODE.md → AGENT.md` |
| `workspace/` | Структурированное хранилище: люди, сервисы, решения, риски, дневник |
| `plans/today.md` | Живой план дня — обновляется агентом автоматически |
| `capture-inbox/` | Входящий поток мыслей — записал, агент разберёт вечером |

#### Автоматизация (сервисы)
| Сервис | Назначение |
|--------|-----------|
| **Mail Agent** | Читает почту фоном, создаёт задачи без участия техлида |
| **Memory Service** | Оперативная память — задачи, инциденты, риски, люди с MCP-интерфейсом |
| **RAG Service** | База знаний команды — семантический поиск по документации |
| **AI-Agent** | Всегда в контексте — читает workspace, управляет через MCP tools |

> *Разработчик не пишет код в блокноте. **Техлид не должен управлять командой в чатах и Excel.***

---

### Слайд 4 — Technology Architecture

**Как построен LeaderOS**

```
🏢 Enterprise Systems
   Exchange · Jira · Confluence · GitHub/Bitbucket · Kubernetes
         │
         ↓ MCP · REST · EWS/IMAP
⬡ LeaderOS Platform
   common :library
     AgentClient → Claude · Codex · Ollama · GigaChat · Mock
   JavaMailAgent :8080
     Mail routing → PG: mailagent
   JavaMemoryService :8082
     Operational Memory · Plugin Control Plane → PG: memory
   JavaRagService :8081
     RAG indexing + search → PG: rag + OpenSearch
   Workspace files
     capture-inbox · plans · drafts · rag-inbox

🤖 AI Runtime
   Interactive Agent · Claude Code · Codex · Test Runner · Arch Analyst
   Ollama mxbai-embed-large
```

**Ключевые тезисы:**
- `common :library` скрывает конкретного AI-провайдера через `AgentClient`
- все агенты работают через `Memory Service` и MCP tools
- `Plugin Control Plane` управляет интеграциями из единого UI

**Технологический стек:**
```
Java 21 · Spring Boot 3 · Spring AI · MCP tools
PostgreSQL · Flyway · OpenSearch · Ollama · Kubernetes-ready
```

---

### Слайд 5 — Product Architecture

**LeaderOS — операционная система для технического лидера**

Слои продукта:
- **AI Agents Layer** — Interactive Agent, Claude Code, Codex, Test Runner, Arch Analyst
- **Operational Memory Layer** — задачи, риски, инциденты, люди, заметки, daily plan
- **Knowledge Layer** — документация, ADR, RFC, service cards, semantic search, RAG
- **Automation Layer** — mail routing, capture bot, schedulers, daily cycle, plugin control
- **Enterprise Integration Layer** — Exchange, Jira, Confluence, GitHub/Bitbucket, Kubernetes, Calendar

**Главный тезис:** техлид получает не чатбота, а рабочую среду, которая объединяет память, знания, автоматизацию и агентов в одну систему.

---

### Слайд 6 — Сценарий 1: Письмо → Задача · Знания

**Боль:** Письма теряются в потоке. Важные запросы не фиксируются. Полезные знания из писем никуда не сохраняются.

**Триггер:** Шедулер каждые 60 сек сканирует почту

**Flow:**
```
📬 Exchange EWS
    → Mail Agent Scheduler
    → AI-Agent (5 маршрутов):
        REQUEST  → Memory Service /api/tasks/pending  → PENDING задача → подтверждение в UI
        CAPTURE  → Memory Service /api/capture
        NOTICE   → rag-inbox/ → RAG Scheduler → OpenSearch (векторная база)
        DRAFT    → drafts/
        NOISE    → ✓ прочитано
```

**Результат:** 5 маршрутов — каждое письмо попадает куда надо.

---

### Слайд 7 — Сценарий 2: Capture Bot → Классификация

**Боль:** Мысли теряются в течение дня. Некогда классифицировать — надо просто записать и двигаться.

**Триггер:** `"запомни: Иванов хочет перейти в другую команду"`

**Flow:**
```
💬 Заметка тимлида
    → POST /api/capture Memory Service
    → capture-inbox/ (без интерпретации)
    → Шедулер 18:00 → AI-Agent классификация:
        TASK        → PENDING задача
        RISK        → Реестр рисков
        KNOWLEDGE   → RAG база знаний
        PERSON_NOTE → Карточка человека
        JOURNAL     → Daily Journal
```

**Результат:** Ни одна мысль не теряется. Всё разложено по местам вечером автоматически.

---

### Слайд 8 — Сценарий 3: Поиск по документации

**Боль:** Поиск в Confluence занимает 20–30 минут. Документация устаревшая. Никто не знает где что лежит.

**Триггер:** `"как у нас проходит релиз?"`

**Flow:**
```
❓ Вопрос тимлида
    → AI-Agent интерактивный
    → MCP rag_search → RAG Service :8081
    → embeddings → Ollama mxbai-embed-large
    → kNN поиск → OpenSearch (векторное хранилище)
    → top-3 чанка → релевантные фрагменты
    → 💬 Ответ с источниками за ~3 секунды
```

**Результат:** Ответ за 3 секунды с указанием источника. Поиск на русском языке.

---

### Слайд 9 — Сценарий 4: Утренний контекст дня

**Боль:** Каждое утро 15–20 минут на восстановление контекста — что горит, что ждёт, кто что делает.

**Триггер:** Начало рабочего дня — открываю LeaderOS

**Flow:**
```
☀️ Начало дня
    → AI-Agent MCP getContext
    → Memory Service /api/context
        → Задачи сегодня + завтра
        → Открытые инциденты
        → Открытые риски
        → Заметки о людях
    → 📋 Брифинг дня: приоритеты + риски + люди
```

**Результат:** За 30 секунд полный контекст: что горит, что ждёт, кто что делает.

---

### Слайд 10 — Сценарий 5: Фиксация инцидента

**Боль:** Инциденты фиксируются хаотично. Нет истории. Одни и те же проблемы повторяются.

**Триггер:** `"зафикси P1: платежи не проходят"`

**Flow:**
```
🚨 Сообщение тимлида
    → AI-Agent показывает карточку
    → подтвердить → MCP createIncident P1 · OPEN
    → Memory Service: Инцидент зафиксирован
    → OPEN → INVESTIGATING
    → решено → MCP resolveIncident (root_cause + action_items)
    → ✅ RESOLVED: Постмортем сохранён
    → виден в 📊 Утренний брифинг
```

**Результат:** История инцидентов, постмортемы, паттерны проблем — всё в одном месте.

---

### Слайд 11 — Roadmap

#### ✅ Реализовано
- 📧 Mail Agent + Exchange EWS
- 🧠 Memory Service + UI
- 🔍 RAG + поиск на русском
- 🗺️ Arch Analyst агент
- 🔗 MCP: Jira, Confluence, k8s
- 📝 Capture Bot
- 🧪 E2E: 44 PASS / 0 FAIL
- 🧩 Common LLM модуль (GigaChat · YandexGPT · Claude)

#### 🔜 В разработке
- 📊 **Capacity Dashboard** — загрузка, выгорание, спринт
- 🌅 **End of Day Summary** — итоги, незавершённое, завтра
- 📅 **Weekly Routine Manager** — 1-on-1, ретро, планирование
- ⚡ **Proactive Briefing** — алерты, аномалии, превентив
- 🔄 **Daily Cycle** — календарь → брифинг по встрече, утренний старт, вечерний итог, настройка ежедневных активностей
- 💬 **СберЧат Agent** — разбор чата → задачи, знания

#### 🚀 Для трайба
- 🖥️ LeaderOS Hub UI
- ☸️ Деплой на корп k8s
- 👥 Multi-tenant тимлидов
- 📈 Grafana метрики трайба
- 📚 Общая база знаний
- 🔒 Интеграция с корп SSO

---

### Слайд 12 — Dev Flow

**Идея → CR → Agent → Code**

```
💡 Идея (пользователь)
    → ChatGPT (автор CR)
    → CR · docs/cr/ · git commit → repo
    → GitHub Issue
    → Claude Code / Codex Agent (исполнитель)
        → Изменения в коде
        → Обновление документации
    → E2E Тесты
    → ✅ Пользователь проверяет
    → ☁️ Google Drive (Source of Truth)
        ↩ актуальная документация перед каждым новым CR
```

> **ChatGPT** — автор и архитектор. **Claude Code / Codex** — исполнитель. **Google Drive** — единственная истина.

---

### Слайд 13 — Организация документации

**RFC на модуль. ARCHITECTURE.md — одна мастер-спека для всех агентов.**

Совместима с: 🐧 SberOS · 🐧 Ubuntu · 🍎 macOS · UNIX

```
📄 ARCHITECTURE.md  (мастер-спека · корень проекта)
   ↑               ↑               ↑               ↑
   🔗 symlink     🔗 symlink     🔗 symlink     🔗 symlink
   JavaMailAgent  JavaMemoryService JavaRagService  common
   ARCHITECTURE.md ARCHITECTURE.md ARCHITECTURE.md ARCHITECTURE.md
   │               │               │               │
   📋 RFC-         📋 RFC-         📋 RFC-         📋 RFC-
   JavaMailAgent  memory-service  rag-service     common
```

> Агент в любом модуле открывает **ARCHITECTURE.md** — и сразу видит всю систему. **RFC** описывает детали конкретного сервиса.

---

### Слайд 14 — Финал

**Спасибо за внимание. Ваши вопросы?**

`Java · Spring Boot 3` · `AI-Agent · RAG` · `k8s-ready` · `работает сейчас`

---

## Технические параметры презентации

| Параметр | Значение |
|----------|----------|
| Формат | HTML single-file, fullscreen |
| Слайдов | 14 |
| Ориентировочное время | ~14 минут |
| Навигация | Клавиши ←/→, свайп, точки |
| Диаграммы | Mermaid.js 10 (flowchart) |
| Шрифты | JetBrains Mono, Inter |
| Тема | Dark (GitHub-style dark) |
| Fullscreen | клавиша `F` |

---

## Ключевые тезисы для выступления

1. **Масштаб боли:** 3 840 часов/год = 2 FTE — цифра, которая говорит за себя
2. **Концепция:** IDE для разработчика, только для техлида — аналогия понятна любому инженеру
3. **Production-ready:** не прототип — работает сейчас, 44 E2E тестов, реальные интеграции
4. **5 сценариев:** каждый закрывает реальную боль, есть до/после
5. **Dev workflow:** система разрабатывается самой собой — ChatGPT пишет спеки, Claude Code реализует
6. **Scale path:** от персонального инструмента → к платформе трайба

---

## Связанные документы

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — мастер-спека всей системы
- [`JavaMailAgent/RFC-JavaMailAgent.md`](JavaMailAgent/RFC-JavaMailAgent.md) — RFC Mail Agent
- [`JavaMemoryService/RFC-memory-service.md`](JavaMemoryService/RFC-memory-service.md) — RFC Memory Service
- [`JavaRagService/RFC-rag-service.md`](JavaRagService/RFC-rag-service.md) — RFC RAG Service
