# CR-UI-015: Новый слайд презентации --- Agent AI Layer

**Тип:** Enhancement\
**Приоритет:** High\
**Статус:** Draft

## Цель

Добавить новый слайд между **Product Architecture** и **Mail Flow**.

Основная идея:

> LeaderOS не зависит от конкретного AI-агента.

Система использует единый Prompt Contract (`AGENT.md`) и единый Java API
(`AgentClient`), поэтому может работать с Claude, Codex, GigaChat,
Ollama и другими AI Runtime без изменения бизнес-логики.

## Архитектура

### Prompt Contract

Все AI используют единый файл:

-   AGENT.md

Через symbolic links:

-   CLAUDE.md → AGENT.md
-   CODEX.md → AGENT.md
-   GIGACODE.md → AGENT.md

### Java API

Модуль `common` содержит интерфейс:

`AgentClient`

Все сервисы используют только этот интерфейс:

-   Mail Agent
-   Memory Service
-   RAG Service

Поддерживаемые реализации:

-   Claude
-   Codex
-   GigaChat
-   Ollama
-   Mock

## Mermaid

``` mermaid
flowchart TB
    AGENT["AGENT.md"]

    CLAUDE["CLAUDE.md"]
    CODEX["CODEX.md"]
    GIGA["GIGACODE.md"]

    CLAUDE -.-> AGENT
    CODEX -.-> AGENT
    GIGA -.-> AGENT

    AGENT --> CLIENT["AgentClient"]

    MAIL["Mail"]
    MEMORY["Memory"]
    RAG["RAG"]

    MAIL --> CLIENT
    MEMORY --> CLIENT
    RAG --> CLIENT

    CLIENT --> CLAUDE_RT["Claude"]
    CLIENT --> CODEX_RT["Codex"]
    CLIENT --> GIGA_RT["GigaChat"]
    CLIENT --> OLLAMA_RT["Ollama"]
    CLIENT --> MOCK_RT["Mock"]
```

## Правая карточка

**Главная идея**

AI является сменным компонентом системы.

LeaderOS использует единый Prompt Contract и единый Java-интерфейс.

Для перехода на нового AI достаточно заменить Provider.

## Итоговый тезис

> Меняется AI Runtime --- архитектура LeaderOS остаётся неизменной.

## Acceptance Criteria

-   Добавлен новый слайд.
-   Используется Mermaid.
-   Показаны Prompt Contract и AgentClient.
-   Отображены Claude, Codex, GigaChat, Ollama и Mock.
-   Обновлены навигация и нумерация презентации.
