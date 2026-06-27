# CR-ARCH-003: AGENT.md v2.0 — синхронизация агента с архитектурой LeaderOS Core

**Дата:** 2026-06-14
**Статус:** Approved
**Сервис:** ARCH
**Зависимости:** CR-ARCH-002, CR-MEM-003

## Проблема

Текущий AGENT.md не отражает новую архитектуру, в которой JavaMemoryService является LeaderOS Core и единой точкой входа для AI-агентов.

## Цель

Полностью актуализировать AGENT.md под новую архитектуру:

- Memory Service = LeaderOS Core
- searchKnowledge = основной механизм поиска знаний
- JavaRagService = внутренний knowledge backend
- единый вход через Memory MCP

## Основные изменения

### 1. Новая роль агента
Заменить `Tech Lead Onboarding Agent` на `LeaderOS Personal Tech Lead Agent`.

### 2. Добавить раздел LeaderOS Core
- JavaMemoryService является единой точкой входа для AI-агента.
- Внешние агенты не должны обращаться напрямую к JavaRagService.
- Основные инструменты: getContext, getTasks, getRisks, getPeople, searchKnowledge.

### 3. Переписать раздел Источники данных
Приоритет №1 — Memory MCP.
Приоритет №2 — Confluence, Jira, Github.
Приоритет №3 — filesystem.

### 4. Добавить раздел Knowledge Search
Основной инструмент: searchKnowledge.

Flow:
Agent → Memory MCP → /api/knowledge/search → JavaRagService + Operational Context

### 5. Обновить Capture Bot
Основной путь: POST /api/capture.
Fallback: capture-inbox/.

### 6. Добавить Agent Workflow
Получить вопрос → Получить контекст → searchKnowledge → Ответ → Предложить действие.

### 7. Будущие источники знаний
RAG, Mail, Jira, Confluence, Calendar, Meetings, Tasks, Risks, Notes, People.

### 8. Метрики использования
KNOWLEDGE_SEARCH, TASK_CREATED, TASK_COMPLETED, MAIL_TASK_CREATED, CAPTURE_CREATED, CAPTURE_PROCESSED.

### 9. Workspace
Workspace = рабочая область и артефакты.
Memory Service = источник истины.

## Acceptance Criteria

- AGENT.md полностью переписан
- Добавлен раздел LeaderOS Core
- Добавлен раздел Knowledge Search
- Добавлен раздел Agent Workflow
- Добавлен раздел Метрики использования
- Добавлен searchKnowledge
- Удалены рекомендации прямого использования JavaRagService
