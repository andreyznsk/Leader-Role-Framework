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
