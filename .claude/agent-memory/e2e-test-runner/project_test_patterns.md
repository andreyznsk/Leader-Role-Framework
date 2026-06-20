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

## Ollama порты

- Локальная Ollama: localhost:11434 (рабочая)
- Docker Ollama: 11435 (недоступен, не используется)
- Модели: mxbai-embed-large, zylonai/multilingual-e5-large, qwen3:8b
