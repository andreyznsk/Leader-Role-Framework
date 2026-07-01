# 2026-06-30_CR-MEM-022: Left Sidebar Navigation

**Дата:** 2026-06-30  
**Статус:** Implemented  
**Сервис:** MEM / JavaMemoryService UI  
**Тип:** UI / Navigation  
**Ветка:** `feature/MEM-019-2026-06-30`

## Проблема / Мотивация

Сейчас глобальная навигация UI LeaderOS фактически развивается как набор отдельных страниц `/ui/*` и верхних ссылок. По мере роста продукта это становится неудобно:

- страниц становится больше: Today, Notes, Captures, Knowledge, Global Search, Settings, Stats, Agent Workspace и другие;
- верхнее меню плохо масштабируется по количеству разделов;
- нет смысловой группировки разделов по рабочим сценариям техлида;
- пользователь не видит LeaderOS как единую рабочую среду, а скорее как набор отдельных экранов.

Нужно перейти к более продуктовой навигации: левое боковое меню, похожее на навигацию IDE / workspace-приложений.

## Решение

Сделать единый левый sidebar для UI JavaMemoryService.

Ключевые требования:

1. Заменить глобальное верхнее меню на левое боковое меню.
2. Sidebar должен быть виден на всех основных страницах MemoryService UI.
3. Sidebar должен поддерживать collapse / expand:
   - expanded: иконка + название раздела + группы;
   - collapsed: только иконки, контент занимает больше ширины;
   - состояние можно хранить в `localStorage` браузера.
4. Навигация должна быть сгруппирована по темам.
5. Активный пункт меню должен подсвечиваться по текущему URL.
6. На мобильной / узкой ширине sidebar должен работать как drawer или автоматически сворачиваться.
7. Реализация должна быть общей, а не копипастой по страницам.

## Предлагаемая структура меню

### Операционная работа

- Today — `/ui/today`
- Tasks / Pending — если отдельной страницы нет, использовать текущий Today/Pending flow
- Notes — `/ui/notes`
- Captures — `/ui/captures`

### Контекст и риски

- People — `/ui/people`
- Risks — `/ui/risks`
- Incidents — `/ui/incidents`

### Знания

- Global Search — `/ui/search`
- Knowledge — `/ui/knowledge`
- Documentation — если отдельной страницы пока нет, пункт не добавлять или пометить как future

### Автоматизация

- Agent Workspace — `/ui/agent-workspace`
- Settings / Control Plane — `/ui/settings`
- Stats — `/ui/stats`

### Система

- Health / Status — если есть UI endpoint, добавить; если нет, не добавлять в MVP
- Plugins — если остаётся частью `/ui/settings`, отдельный пункт не нужен

## UX-поведение

### Expanded state

Sidebar шириной примерно `240–280px`:

- сверху логотип / название `LeaderOS`;
- рядом или ниже кнопка collapse;
- группы с заголовками;
- элементы: иконка, label, optional badge;
- активный пункт подсвечивается.

### Collapsed state

Sidebar шириной примерно `64–72px`:

- показываются только иконки;
- group labels скрываются;
- tooltip по hover желателен, но не обязателен для MVP;
- main content расширяется.

### Mobile

Для ширины меньше tablet breakpoint:

- sidebar по умолчанию скрыт;
- появляется кнопка меню;
- sidebar открывается как overlay drawer;
- клик по пункту закрывает drawer.

## Техническая реализация

Рекомендуемый подход:

1. Найти общий layout / fragment для Thymeleaf UI JavaMemoryService.
2. Вынести sidebar в общий Thymeleaf fragment, например:
   - `templates/fragments/sidebar.html`
   - или существующий общий layout, если он уже есть.
3. Подключить sidebar во все основные UI pages:
   - `today.html`
   - `notes.html`
   - `captures.html`
   - `knowledge.html`
   - `settings.html`
   - `stats.html`
   - `agent-workspace.html`
   - `people.html`, `risks.html`, `incidents.html`, если существуют.
4. Добавить общий CSS:
   - `static/css/app.css`, `static/css/navigation.css` или существующий файл стилей.
5. Добавить JS для collapse state:
   - `localStorage.setItem('leaderos.sidebar.collapsed', 'true|false')`;
   - при загрузке страницы применять состояние до основного рендера, чтобы избежать скачка layout.
6. Не менять backend API.

## Изменения в API

Не требуются.

## Изменения в схеме БД

Не требуются.

## Зависимости

- JavaMemoryService Thymeleaf templates.
- Существующие `/ui/*` маршруты.
- Текущие CSS/JS ресурсы MemoryService UI.

## Acceptance Criteria

- [x] На всех основных страницах MemoryService UI отображается единый левый sidebar.
- [x] Верхнее глобальное меню удалено или сведено к минимальному header без дублирования навигации.
- [x] Разделы меню сгруппированы по темам.
- [x] Активный пункт меню подсвечивается по текущему URL.
- [x] Sidebar можно свернуть и развернуть.
- [x] Состояние collapse сохраняется между переходами страниц через `localStorage`.
- [x] В collapsed mode основной контент занимает дополнительное место.
- [x] На узком экране sidebar не ломает контент и работает как drawer / overlay или автоматически скрывается.
- [x] Существующие UI страницы продолжают открываться по старым URL.
- [x] Нет изменений в REST API и схеме БД.

## Как тестировать

### Manual UI smoke

1. Запустить JavaMemoryService локально.
2. Открыть `/ui/today`.
3. Проверить, что слева отображается sidebar.
4. Перейти через меню на:
   - `/ui/notes`
   - `/ui/captures`
   - `/ui/knowledge`
   - `/ui/settings`
   - `/ui/stats`
   - `/ui/agent-workspace`
5. Проверить активное состояние пунктов.
6. Свернуть меню.
7. Перейти на другую страницу.
8. Проверить, что меню осталось свернутым.
9. Развернуть меню.
10. Проверить адаптивность на узкой ширине окна.

### E2E / smoke

Обновить или добавить UI smoke-сценарий для JavaMemoryService:

- `JavaMemoryService/test_e2e/11_ui_smoke.md`

Проверить, что основные страницы возвращают HTTP 200 и содержат общий navigation marker, например:

```html
<nav data-testid="leaderos-sidebar">
```

## После реализации

После подтверждения пользователя перевести этот CR в статус `DONE` / `Implemented` и обновить реестр `docs/cr/REGISTRY.md`.

## Реализация (2026-07-01)

- `fragments/layout.html` — `nav`-фрагмент заменён на `sidebar` (collapse/expand, localStorage, mobile drawer, active-highlight); `head`-фрагмент получил anti-FOUC inline script.
- `static/style.css` — `.los-navbar`/`.los-links` удалены, добавлены `.los-sidebar`/`.los-nav-item` и CSS-переменная `--los-sidebar-w`; `.presentation-container`/body offset пересчитаны под sidebar-layout.
- Все 14 UI-шаблонов подключены к `fragments/layout :: sidebar`.
- Проверено вручную в браузере (Playwright, desktop 1440px и mobile 390px): рендер, collapse без layout jump, mobile drawer, active state.
- E2E: `test_e2e/20_ui_smoke_sidebar.md` (номер `11` был занят `11_capture_bot_improvements.md`, взят следующий свободный) + `test_e2e/tests/sidebar-navigation.spec.js` (14/14 PASS). Полный regression suite прогнан через `e2e-test-runner` — регрессий от этого CR не найдено (см. `.claude/agent-memory/e2e-test-runner/project_test_patterns.md`).
- RFC обновлён: `JavaMemoryService/RFC/RFC-memory-service.md` — добавлена секция "Глобальная навигация (CR-MEM-022)".
