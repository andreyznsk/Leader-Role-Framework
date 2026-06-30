# PROJECT-INSTRUCTION.md

# LeaderOS Project Instructions

## Mission

LeaderOS — персональный AI-powered Tech Lead Framework.

Цель проекта — автоматизировать операционную работу технического лидера:

* управление задачами;
* управление знаниями;
* обработку почты;
* работу с документацией;
* подготовку изменений;
* работу с архитектурой;
* взаимодействие с локальными AI агентами.

---

## Repository

GitHub repository:
https://github.com/andreyznsk/Leader-Role-Framework.git

Default branch:
feature/mailAg-001

CR directory:
docs/cr/

Issue labels:
enhancement
memory-service
ui

Tools:
GitHub connector
ChatGPT Codex Connector
Permissions
Read access to checks, commit statuses, and metadata
Read and write access to actions, code, issues, pull requests, and workflows
Repository access Selected 1 repository.
andreyznsk/Leader-Role-Framework

Google Календарь

Google Диск

---

# Source of Truth

## Главное правило

Google Drive является единственным официальным Source of Truth.

При конфликте данных:

```text
Google Drive
    >
GitHub docs/
    >
ChatGPT Project Context
    >
Chat Conversation
```

Если информация в Google Drive отличается от GitHub или контекста проекта, приоритет всегда имеет Google Drive.

---

# Документация

Основная документация хранится в Google Drive.

В неё входят:

* README.md
* ARCHITECTURE.md
* AGENT.md
* RFC
* ADR
* Roadmap
* Презентации
* Отчёты
* Service Cards
* Процессная документация

Пользователь вручную поддерживает актуальность документов в Google Drive.

После обновления документации пользователь синхронизирует её с ChatGPT Project Context.

---

# GitHub Workflow

## Назначение

GitHub используется как рабочая staging-зона для будущих изменений.

Основная ветка для взаимодействия ChatGPT и локального агента:

```text
feature/mailAg-001
```

ChatGPT не должен писать артефакты напрямую в master без явного указания пользователя.

---

# Папка docs

Все рабочие артефакты создаются в:

```text
docs/
```

Структура:

```text
docs/
├── cr/
├── rfc/
├── adr/
├── meetings/
├── reports/
├── diagrams/
└── workflow/
```

---

# Change Request Workflow

ввести единый CR-реестр (таблица в Drive или `docs/cr/REGISTRY.md`)

Все изменения начинаются с CR.

Формат имени:

```text
{YYYY-MM-DD}_CR-{PREFIX}-{NNN}-{short-name}.md
```

Примеры:

```text
2026-06-21_CR-MEM-001-memory-stats.md
2026-06-21_CR-RAG-002-index-status.md
2026-06-21_CR-ARCH-003-docs-workflow.md
```

Минимальная структура:

```markdown
# {YYYY-MM-DD}_CR-{PREFIX}-{NNN}: Title

**Дата:** YYYY-MM-DD
**Статус:** Draft | Review | Approved | Implemented
**Сервис:** MEM | RAG | MAIL | COMMON | ARCH

## Проблема / Мотивация

## Решение

## Изменения в API

## Изменения в схеме БД

## Зависимости

## Как тестировать

## После подтверждения пользователя перевести этот CR в Статус: DONE. и обновить реестр таблица `docs/cr/REGISTRY.md`

```

---

# Работа локального агента

После появления нового CR:

```text
git pull
```

локальный агент должен:

1. Найти новые CR.
2. Прочитать CR.
3. Выполнить изменения.
4. Обновить документацию.
5. Прогнать тесты.
6. Сформировать отчёт.

Локальный агент является исполнителем изменений.

ChatGPT является автором изменений и документации.

---

# Полный Flow

```text
Пользователь
    ↓
Идея / проблема
    ↓
ChatGPT
    ↓
Создание CR в GitHub docs/
    ↓
git pull
    ↓
Claude Code / Codex Agent
    ↓
Изменения в коде
    ↓
Обновление документации
    ↓
Тестирование
    ↓
Пользователь проверяет результат
    ↓
Пользователь обновляет Google Drive
    ↓
Пользователь синхронизирует Project Context
    ↓
Google Drive снова становится Source of Truth
```

---

# Работа со встречами

Если встреча связана с изменением:

1. Создать CR в GitHub.
2. Создать встречу в Google Calendar.
3. Добавить ссылку на CR в описание встречи.
4. После завершения работ обновить документацию в Google Drive.

---

# Работа с презентациями

Все промежуточные презентации создаются в:

```text
docs/
└── cr/
    └── presentation/
        ├── YYYY-MM-DD_CR-PRES-XXX-*.md
        ├── presentation-v2.html
        ├── presentation-v3.html
        └── ...
```

Предпочтительный формат:

```text
HTML
```

Допустимые форматы:

```text
HTML
Markdown
Mermaid
PlantUML
```

---

# Работа со схемами

Все схемы создаются в:

```text
docs/diagrams/
```

Предпочтительно:

```text
Mermaid
```

---

# Принцип работы ChatGPT

ChatGPT должен:

* читать документацию из Project Context;
* считать Google Drive главным источником истины;
* создавать новые артефакты в GitHub docs/;
* создавать CR перед крупными изменениями;
* создавать RFC для архитектурных изменений;
* создавать ADR для архитектурных решений;
* помогать пользователю готовить задачи для локального агента.

ChatGPT не должен считать GitHub окончательным источником истины.

GitHub используется только как рабочая зона изменений до публикации в Google Drive.

---

# CR → Issue Workflow

После создания нового CR в GitHub необходимо сразу создавать GitHub Issue на исполнение.

Flow:

```text
Идея
    ↓
CR (docs/cr)
    ↓
GitHub Issue
    ↓
AI Agent / Codex
    ↓
PR
    ↓
Merge
    ↓
Issue Closed
    ↓
CR -> Implemented
```

Правила:

1. Каждый новый CR должен иметь связанный GitHub Issue.
2. Заголовок Issue должен ссылаться на номер CR.
3. В описании Issue обязательно указывается путь к CR.
4. Issue содержит Acceptance Criteria из CR.
5. Локальный агент берет в работу Issue, а не CR напрямую.
6. После завершения реализации Issue закрывается.
7. После успешного merge статус CR переводится в `Implemented`.

Пример:

```text
YYYY-MM-DD_CR-ARCH-004-mail-notice-rag-document-flow.md
    ↓
Issue #7 Implement CR-ARCH-004 Mail NOTICE as RAG document flow
```
