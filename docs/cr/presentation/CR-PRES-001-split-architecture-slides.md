# CR-PRES-001: Split architecture into Technology and Product Architecture slides

**Дата:** 2026-06-28  
**Статус:** Draft  
**Сервис:** PRES  
**Артефакт:** `JavaMemoryService/src/main/resources/static/presentation.html`  
**Source of Truth:** `PRESENTATION.md`

## Проблема / Мотивация

Текущий слайд 4 `Архитектура` смешивает два разных смысла:

1. технологическую реализацию LeaderOS: Java-сервисы, PostgreSQL, OpenSearch, Ollama, MCP, common;
2. продуктовую архитектуру: второй мозг техлида, операционная память, знания, автоматизация, агенты.

Из-за этого слайд перегружен и за короткое время не доносит главную мысль: LeaderOS — это не набор сервисов, а операционная система техлида.

## Решение

Разделить текущий слайд 4 на два слайда:

### Слайд 4 — Technology Architecture

Показывает, как построен LeaderOS технически:

- Enterprise Systems: Exchange, Jira, Confluence, GitHub/Bitbucket, Kubernetes;
- LeaderOS Platform: JavaMailAgent, JavaMemoryService, JavaRagService;
- `common :library` как AI-agnostic слой `AgentClient`;
- Memory Service как центральный hub для MCP и Control Plane;
- RAG Service как knowledge backend;
- Workspace files;
- AI Runtime: Interactive Agent, Claude Code, Codex, Test Runner, Arch Analyst, Ollama.

Ключевой тезис: любой AI-провайдер подключается через `common`, а агенты работают через Memory/MCP.

### Слайд 5 — Product Architecture

Показывает LeaderOS как продуктовую систему:

- AI Agents Layer;
- Operational Memory Layer;
- Knowledge Layer;
- Automation Layer;
- Enterprise Integration Layer.

Ключевой тезис: LeaderOS объединяет память, знания, автоматизацию и агентов в одну операционную систему техлида.

## Изменения в HTML

1. Переработать существующий блок `<!-- 4. АРХИТЕКТУРА -->`:
   - переименовать в `Technology Architecture`;
   - добавить `common :library / AgentClient`;
   - добавить `Plugin Control Plane`;
   - добавить `Workspace files`;
   - явно показать AI Runtime и агентов;
   - оставить стек технологий в нижних карточках.

2. Вставить новый слайд после технологической архитектуры:
   - `<!-- 5. АРХИТЕКТУРА ПРОДУКТА -->`;
   - показать layered product architecture;
   - не перегружать техническими деталями;
   - сделать центральный тезис: `операционная система технического лидера`.

3. Сдвинуть последующие сценарные слайды на один номер логически:
   - бывший слайд 5 становится слайдом 6;
   - финальная презентация становится на 14 слайдов.

4. Проверить навигацию:
   - счётчик слайдов;
   - точки навигации;
   - стрелки;
   - mobile swipe;
   - fullscreen.

## Изменения в PRESENTATION.md

Обновить структуру презентации:

- `Слайд 4 — Technology Architecture`;
- `Слайд 5 — Product Architecture`;
- остальные слайды сдвинуть на +1;
- технические параметры: `Слайдов: 14`, ориентировочное время `~14 минут`.

## Acceptance Criteria

- [ ] В HTML есть отдельный слайд `Technology Architecture`.
- [ ] В HTML есть отдельный слайд `Product Architecture`.
- [ ] `Technology Architecture` показывает `common / AgentClient`, MemoryService, MailAgent, RagService, Workspace, PostgreSQL, OpenSearch, Ollama и AI Runtime.
- [ ] `Product Architecture` показывает layered-модель продукта без перегруза инфраструктурой.
- [ ] Навигация работает корректно, счётчик показывает 14 слайдов.
- [ ] Mermaid-диаграммы рендерятся без ошибок.
- [ ] Презентация остаётся fullscreen single-file HTML.
- [ ] `PRESENTATION.md` обновлён и соответствует HTML.

## Как тестировать

1. Открыть `presentation.html` в браузере.
2. Проверить слайды 4 и 5 визуально.
3. Проверить переходы стрелками ←/→.
4. Проверить навигационные точки и счётчик.
5. Открыть DevTools Console и убедиться, что Mermaid не падает с ошибкой.
6. Проверить, что следующие сценарные слайды идут после Product Architecture.
