---
name: e2e-test-patterns
description: Паттерны выполнения E2E тестов e2e-integration/ — типичные сбои, пути, профили
metadata:
  type: project
---

## Профили запуска

- Профиль `local` (mock.agent=true): IT-01..08 работают
- Профиль `ollama`: требуется для IT-09 (NOTICE flow) — MockClaudeRunner не умеет NOTICE
- При профиле `local` NOTICE-письма классифицируются как REQUEST — это ожидаемо

**Why:** MockClaudeRunner в local-профиле классифицирует по keyword-логике, не знает тип NOTICE.
**How to apply:** при запуске всех тестов с профилем local — IT-09 SKIP, не FAIL.

## Пути rag-inbox

- Сервисы пишут KNOWLEDGE captures в `rag-inbox/captures/` от корня проекта
- Сценарий IT-06 Step 5 ошибочно ищет в `JavaRagService/rag-inbox/captures/` — это неверно
- Правильный путь: `/home/andreyz/IdeaProjects/Leader-Role-Framework/rag-inbox/captures/`

**Why:** BUG-02 был исправлен (path.rag-inbox: rag-inbox), но сценарий не обновлён.

## Известные дефекты сценариев

- IT-06 Step 5: неверный путь grep (`JavaRagService/rag-inbox/captures/` вместо `rag-inbox/captures/`)
- IT-07 Step 9: jq `select(.note | contains($r))` ищет RUN_ID в поле `.note`, а он в `.personName`
- IT-08 Steps 8,9: zsh артефакт `grep -c` возвращает `"0\n0"` при nested вызовах
- IT-09 Step 4: неверный путь grep (`JavaRagService/rag-inbox/mail/` вместо `rag-inbox/mail/`) — тот же паттерн что IT-06
- IT-09 Steps 6, 8, 14: `select(.filePath == $path)` не работает если $path абсолютный, RagService хранит относительный путь
- IT-09 Steps 9, 11–14: jq падает с "Invalid control character" при `.content | split(...)` — markdown с `\r\n` в JSON; использовать grep или `.summary` без `.content`

## IT-09 — результаты запуска с профилем ollama (2026-06-21)

- Все 14 шагов PASS несмотря на дефекты сценария (работали по факту)
- Классификация NOTICE: ~7 сек (первый poll attempt)
- Файл записан в: `rag-inbox/mail/YYYY-MM-DD/{emailId}.md`
- RagService id для NOTICE из mail: числовой, хранится в `/api/rag/status` и `/api/notices`
- PUT /api/notices/{id} → status=outdated; POST /api/notices/{id}/reindex → status=indexed (chunksAdded=5)
- Семантический поиск по RUN_ID работает (score ~0.67)

## Типичное время обработки

- MailAgent poll цикл: 30 сек
- Первая обработка письма: обычно <10 сек (первый attempt)
- RagService индексация scheduler: до 90 сек (обычно <30 сек)

## Ключевые ID для диагностики

- Maildev email.id: строковый (не числовой)
- Task.id: числовой
- emailId в Task = email.id из Maildev

## CRITICAL тесты (01, 02, 03) — все прошли стабильно

Эти три теста прошли полностью за 1 attempt (быстрый poll). Флаки не обнаружены.

## IT-10 — Control Plane Settings

**2026-06-23 первый прогон (18 шагов):** Все 18 шагов PASS после пересборки всех сервисов.

**2026-06-23 второй прогон (21 шаг, расширенный):** Все 21 шаг PASS. Добавлены Step 19-21 (UI-проверки).
- Step 19: GET /ui/settings → HTTP 200 ✅
- Step 20: bootstrap.bundle.min.js в HTML, data-bs-toggle="collapse" (2 элемента), collapse divs (2) ✅
- Step 21: id="plugin-body-mail" отрендерен сервером, data-plugin-code="mail" присутствует ✅
- maildev значение найдено в HTML 2 раза (selected + value) ✅

**Дефект в сервисе:** settings.foldersExclude.value содержит литеральные `\n` в JSON — jq падает.
Workaround: использовать `python3 json.loads(strict=False)` вместо jq для разбора ответов /api/control/settings.
Применять для: /api/control/settings (MA и RAG), /api/settings/control/plugins/*/settings.

**Предварительная пересборка:** пересборка JavaMemoryService обязательна перед запуском IT-10 если исходники изменились.

**Статус сценария:** Steps 10, 14 используют обёртку `{"settings":{...}}` — это правильный формат (сценарий уже исправлен).

## IT-16 — Global Search (CR-MEM-009)

**2026-06-28 первый прогон (8 шагов):** Все 8 шагов PASS. Пересборка потребовалась (исходники обновились после JAR от 2026-06-26).
**2026-06-28 второй прогон (8 шагов):** Все 8 шагов PASS. Пересборка не потребовалась.

- Step 1: POST /api/tasks → HTTP 201, title+description поддерживают unicode ("отпуск"), task id=169
- Step 2: POST /api/search QUICK+TASK → score=0.85, результаты найдены сразу (без индексации)
- Step 3: Мульти-слойный поиск NOTICE/TASK/PEOPLE/RISK/INCIDENT/KNOWLEDGE → HTTP 200, пустые слои не дают ошибок
- Step 4: Пустой query → HTTP 400 (валидация работает)
- Step 5: GET /api/search/layers → 8 слоёв: NOTICE/TASK/PEOPLE/RISK/INCIDENT/KNOWLEDGE available=true; MAIL/CALENDAR available=false
- Step 6: GET /ui/search → HTTP 200
- Step 7: GET /ui/search?q=отпуск → HTTP 200 с "Search LeaderOS" и "отпуск" в теле (4 вхождения)
- Step 8: MAIL layer → HTTP 200, layers=[] в ответе (MAIL отфильтрован), results=[]

**Cleanup:** DELETE /api/tasks/169 → HTTP 204. Одна задача (один curl с `-w "\n%{http_code}"` + `head -n -1`).

**Дефект сценария Step 7:** Команда в сценарии передаёт Кириллицу напрямую в URL (`?q=отпуск`) — curl не кодирует их автоматически и сервис возвращает HTTP 400. Правильный вызов: `curl -G --data-urlencode "q=отпуск" ...` или URL-encode вручную. Это дефект сценария, не сервиса.

## Ollama порты

- Локальная Ollama: localhost:11434 (рабочая)
- Docker Ollama: 11435 (недоступен, не используется)
- Модели: mxbai-embed-large, zylonai/multilingual-e5-large, qwen3:8b

## JavaMemoryService — Playwright layer (test_e2e/tests/*.spec.js)

Отдельный слой от markdown-сценариев (test_e2e/*.md). Настоящий browser E2E через Playwright.

- Расположение: `JavaMemoryService/test_e2e/tests/*.spec.js`, конфиг `JavaMemoryService/test_e2e/playwright.config.js`
- Запуск: `cd JavaMemoryService/test_e2e && npx playwright test tests/<file>.spec.js --reporter=list` (сервис должен быть уже поднят на 8082, профиль local)
- `npx playwright install chromium` — обычно уже установлен, быстрая no-op проверка не помешает
- baseURL по умолчанию `http://127.0.0.1:8082`, переопределяется `PLAYWRIGHT_BASE_URL`

### Дефект теста task-timeline-ui.spec.js (обнаружен 2026-07-01, CR MEM-023 branch)

`page.getByRole('button', { name: /Сохранить/, exact: true }).click()` падает с strict mode violation —
regex `/Сохранить/` матчит ОБА кнопки: "💾 Сохранить" (value="save") и "✅ Сохранить и закрыть" (value="save_close"),
т.к. `exact: true` не действует на regex-матчер в Playwright (exact применяется только к строкам).
Обе кнопки существуют в `task-edit.html` (строки ~87-92) намеренно — это ожидаемая структура.

**Why:** Дефект в самом тесте (task-timeline-ui.spec.js:34), не в приложении. Это не связано с CR-MEM-023 изменениями напрямую, но тест был обновлён "for new HTML structure" в рамках той же ветки и не обновлён до конца.
**How to apply:** Рекомендуемый фикс — использовать более специфичный локатор, например `page.locator('button[value="save"]')` (как в task-edit-right-control-panel.spec.js) вместо `getByRole` с неточным regex.

### CR-MEM-023 прогон (2026-07-01, branch feature/MEM-023-2026-06-30)

- task-edit-right-control-panel.spec.js: все 9 тестов PASS (data-testid="task-control-panel", data-testid="task-status-select", data-testid="task-timeline", #delete-btn — все селекторы найдены и работают)
- task-timeline-ui.spec.js: 1/1 FAIL — исключительно из-за regex-локатора выше, сервер не логировал ошибок
- Билд JavaMemoryService прошёл чисто, `mvn package -q -DskipTests`, JAR стартовал за ~2.6 сек
- Замечен warning при старте: `Schema "memory" has a version (19) that is newer than the latest available migration (18)` — БД опережает миграции в коде ветки; не блокирует тесты, но стоит проверить при мердже

### РЕАЛЬНЫЙ баг приложения найден за regex-локатором (2026-07-01, CR-MEM-023)

После фикса regex-локатора тест всё равно падал: `#task-timeline-list .border-bottom` находил 2 элемента вместо 5 (комментарии не добавлялись).

**Root cause:** в `task-edit.html` `<form id="timeline-comment-form">` (блок комментария в Timeline) был вложен ВНУТРЬ основной `<form id="task-edit-form">`. HTML5 не допускает вложенные формы — браузер молча игнорирует открывающий тег внутренней формы при парсинге, поэтому:
1. `document.getElementById('timeline-comment-form')` возвращает `null` → обработчик `submit` в `<script>` никогда не навешивается.
2. Кнопка "Добавить" (`type="submit"`) фактически принадлежит ВНЕШНЕЙ форме (браузер ищет ближайшего предка `<form>`), поэтому клик сабмитит основную edit-форму (`PUT /ui/tasks/{id}/edit`) вместо `POST /api/tasks/{id}/timeline/comment`.
3. Комментарии никогда не создаются, `#task-timeline-list` не растёт.

Проверено вручную через `page.evaluate` в headless Chromium: `document.getElementById('timeline-comment-form')` → `null`, `button.form.id` → `"task-edit-form"`.

**Фикс:** заменить `<form id="timeline-comment-form">` на `<div id="timeline-comment-form">`, кнопку — на `type="button"` с явным `id="timeline-comment-submit"`, обработчик — на `click` вместо `submit` (без `event.preventDefault()`, он больше не нужен).

**Why:** это баг приложения, а не только теста — обнаруживается только через реальный браузерный рендеринг (curl/статический HTML-парсинг его не покажет, т.к. разметка "выглядит" валидной без учёта правил авто-закрытия вложенных форм).
**How to apply:** при рефакторинге task-edit.html (или любого шаблона с несколькими `<form>` на странице) — проверять, что комментарий/побочные формы НЕ вложены в основную форму. Быстрая диагностика: `page.evaluate(() => document.getElementById('<id>'))` возвращает `null`, если элемент "потерялся" при парсинге.

### Финальный прогон всего test_e2e/tests/*.spec.js после фикса (2026-07-01)

- 53/54 PASS. Единственный FAIL: `capturebot-ui.spec.js` — "clicking filter button reloads history with correct status" (таймаут `waitForResponse`).
- Этот тест падает и при полном прогоне, и при `--workers=1`, но проходит при запуске в изоляции (`-g` только этот тест) — предсуществующий order-dependent флейк в `capturebot-ui.spec.js`, НЕ связан с изменениями CR-MEM-023 (файл не менялся в этой ветке).
- Дополнительно найден и исправлен `today-ui.spec.js` (не входил в исходный diff CR, но сломан теми же иконками на кнопках Save/Save-close): `getByRole('button', { name: 'Сохранить', exact: true })` не матчит `"💾 Сохранить"` — заменено на `button[value="save"]` / `button[value="save_close"]`, тот же паттерн что и в task-timeline-ui.spec.js.
- Сервис нужно перезапускать через `./test-runner/start-services.sh --service JavaMemoryService --profile local` (не голым `java -jar`) — иначе поднимается на дефолтном H2 без профиля `local`, что ломает Flyway-миграции (`JSONB` не поддерживается H2 → CRASH при старте).

### CR-MEM-022 прогон (2026-07-01, sidebar-navigation.spec.js, branch feature/MEM-023-2026-06-30)

Новый тест `sidebar-navigation.spec.js` (14 тестов) для рефакторинга layout.html (topbar → left sidebar). 12/14 PASS, 2 FAIL — оба FAIL являются багами САМОГО ТЕСТА, не приложения:

1. **"collapsing the sidebar persists across navigation via localStorage" (строка 27-44)** — FAIL.
   Причина: `beforeEach` вызывает `page.addInitScript(() => localStorage.removeItem('leaderos.sidebar.collapsed'))`.
   `addInitScript` выполняется ПЕРЕД КАЖДОЙ навигацией в рамках теста (не только один раз при первом goto) — так что `page.goto('/ui/notes')` внутри теста заново стирает только что установленный `collapsed=true` ДО того как head-скрипт layout.html успевает его прочитать.
   Подтверждено вручную (debug-скрипт с `chromium.launch()`): localStorage реально становится `null` после второй навигации именно из-за init-script, а не из-за багов в приложении.
   **Фикс теста:** выносить `addInitScript` только в `test.beforeEach` для первого goto, либо использовать `page.evaluate(() => localStorage.removeItem(...))` один раз до первого `page.goto`, а не `addInitScript`.

2. **"mobile viewport hides sidebar behind a drawer toggle" (строка 46-60)** — FAIL на первой же проверке (до клика на toggle).
   Тест использует `await expect(sidebar).toBeInViewport({ ratio: 0 })` ожидая что это подтверждает "sidebar скрыт". На деле по докам Playwright 1.61: `ratio: 0` означает "element should intersect viewport at any positive ratio" — т.е. это требование, что элемент ЧАСТИЧНО виден, полная противоположность намерению теста.
   Подтверждено вручную: на mobile viewport 390×844 сайдбар реально скрыт через `transform: translateX(-280px)` (bounding box `x:-280, width:280` — впритык к левому краю, ratio пересечения = 0), т.е. верстка работает корректно.
   **Фикс теста:** заменить на `await expect(sidebar).not.toBeInViewport();` для проверки состояния "скрыт", `toBeInViewport()` без опций — уже используется в этом же тесте после клика на toggle (строка 55) для проверки "виден", это корректно.

**Why:** оба дефекта — в тесте, не в приложении (`layout.html` рефакторинг сам по себе не сломан). Верифицировано отдельными debug-скриптами через `chromium.launch()` напрямую (не через test runner), логируя `localStorage`/`classList`/`boundingBox` вручную.
**How to apply:** при повторных прогонах sidebar-navigation.spec.js — эти 2 FAIL ожидаемы, пока тест не подправлен. Не относить на счёт регрессии layout/CSS.

**UPDATE 2026-07-01 (тот же день):** оба фикса применены в `sidebar-navigation.spec.js` — `beforeEach` теперь делает `page.goto('/ui/today')` + `page.evaluate(() => localStorage.removeItem(...))` вместо `addInitScript`; mobile-тест использует `.not.toBeInViewport()`. Повторный прогон: 14/14 PASS. Больше не ожидать эти 2 FAIL.

### Полный прогон test_e2e/tests/*.spec.js после CR-MEM-022 (2026-07-01)

68 тестов всего (54 старых + 14 новых sidebar). 57 PASS, 11 FAIL:
- 2 FAIL — sidebar-navigation.spec.js (баги теста, см. выше)
- 1 FAIL — capturebot-ui.spec.js "clicking filter button reloads history with correct status" — уже известный order-dependent флейк (см. ниже, не связан с CR-MEM-022)
- 8 FAIL — capturebot-ui.spec.js секция "4. CaptureRouter — all route types create downstream entities" (TASK/RISK/NOTE/QUESTION/KNOWLEDGE/PERSON_NOTE), все с одинаковым паттерном: `expect(capture.routedTo).toContain('risks'/'notes'/...)` получает `"intake/{uuid}"` вместо прямого пути.

**Root cause (НЕ регрессия CR-MEM-022):** коммит `23150e4 "Intake done"` (до текущей ветки) переписал `CaptureRouter.route()` — теперь ВСЕГДА создаёт `IntakeItemDto` через `IntakeService.create()` и возвращает `"intake/" + created.id()`, вместо прямого роутинга в notes/risks/tasks/etc. Это архитектурное изменение (Intake Gateway workflow — с этим связан новый пункт "Intake Gateway" в сайдбаре из CR-MEM-022). Файл `capturebot-ui.spec.js` не был обновлён под новую архитектуру и содержит устаревшие ассерты на прямой роутинг.
Подтверждено: `git status` показывает что в текущей ветке изменены только `.html`/`style.css` (layout-related), Java-код `CaptureRouter.java`/`CaptureProcessingService.java` не менялся — то есть это pre-existing несоответствие теста архитектуре, никак не связанное с сегодняшним sidebar CR.
**How to apply:** при будущих прогонах capturebot-ui.spec.js секция 4 — ожидать эти 8 FAIL пока тест не переписан под Intake Gateway (например: assert `capture.routedTo` начинается с `"intake/"`, затем проверять `suggestedRoute`/`GET /api/intake/{id}` вместо `GET /api/risks|notes|...`). Не путать с регрессией от layout/CSS изменений.
- Другие сьюты (today-ui, task-edit-right-control-panel — включая mobile viewport тест, search-ui, task-timeline-ui, 14_today_hide_done_filter) — 26/26 PASS, без регрессий от sidebar/CSS изменений.
- В логах JavaMemoryService.log во время прогона — только `FileAlreadyExistsException: capture-inbox/.../HH-mm-ss-N.md` (коллизия имени файла при параллельном создании captures в capturebot-ui.spec.js, `fullyParallel: true` в конфиге) — инфраструктурный флейк, не связан с layout.

### Важно: путь до корня репозитория в этой сессии

В этой сессии реальный корень репозитория — `/home/andreyz/IdeaProjects/claude/Leader-Role-Framework/` (git worktree с префиксом `claude`), а НЕ `/home/andreyz/IdeaProjects/Leader-Role-Framework/` (путь без `claude/`, который иногда фигурирует в системных промптах/описании агента). Проверять фактический cwd/наличие файлов перед использованием пути из инструкций — раньше приводило к ложному "AGENT.md недоступен".

### task-done-confirm.spec.js (2026-07-01, window.confirm перед DONE)

Новый тест для CR: window.confirm() перед переводом задачи в DONE в двух местах — чекбокс "Выполнено" на /ui/today (today.html toggleDone) и выбор статуса DONE в /ui/tasks/{id}/edit (task-edit.html submit handler). 5/5 PASS с первого прогона, без правок теста и без правок кода.

Перед запуском потребовалась пересборка — `find JavaMemoryService/src -newer JavaMemoryService/target/memory-service.jar` показал, что `today.html`/`task-edit.html` новее JAR-а (правки CR ещё не собраны). Общий паттерн: перед любым новым test_e2e/tests/*.spec.js всегда проверять этой командой, не отставать ли JAR от исходников, а не полагаться на то что сервис "уже вроде запущен".

## MailAgent E2E — инфраструктурные паттерны (2026-06-25)

### Maildev доступ
- HTTP API (для MailAgent и тестов): `http://172.80.2.1:18080` — корректный URL
- Тестовый сценарий 08 использует `http://localhost:1080` — неверно, maildev UI порт это 18080 на host
- Удаление всех писем: `curl -X DELETE http://172.80.2.1:18080/email/all`
- SMTP для отправки: `python3 smtplib` через `172.80.2.1:1025` (docker bridge gateway)
- SMTP через `localhost:1025` не работает надёжно (curl exit 56, python3 "Connection unexpectedly closed")
- psql не установлен на хосте — использовать `docker exec leader-postgres psql`

### Баги ветки feature/mailAg-001 (ИСПРАВЛЕНЫ в сессии 2026-06-25)

1. **MemoryServiceClient — нет @Autowired**: два конструктора без аннотации → Spring 6 STARTUP FAILED
   - Фикс: `@Autowired` на публичный конструктор
2. **processed_at NOT NULL**: V1 migration: NOT NULL + DEFAULT NOW(), но `newProcessing()` передаёт null → INSERT FAILS
   - Фикс: V5 migration `ALTER COLUMN processed_at DROP NOT NULL`

### Дефекты сценария 08

- **Step 5**: `grep -c "Retry memory delivery task" plans/today.md` → 0 (ожидается 1)
  - MockAgentClient пишет `"Mock task from email <emailId>"`, не subject письма
  - Правильный grep: `grep -c "Mock task from email" plans/today.md`
- **Step 8**: `PROCESSED|NONE|1` → фактически `PROCESSED|NONE|2`
  - При медленном старте memory-service происходит 2 неудачных retry вместо 1
  - Тест должен проверять `attempts_count >= 1`, не точно `= 1`

### Retry flow — ключевые факты

- Retry-очередь обрабатывается РАНЬШЕ новых писем в каждом poll
- Задача в memory-service создаётся ровно 1 раз даже при multiple retries
- Plan line добавляется на PLAN_APPEND-маршруте — до MEMORY_PENDING_TASK — 1 раз, не дублируется
- attempts_count = число неудачных попыток (не включает успешный retry)
