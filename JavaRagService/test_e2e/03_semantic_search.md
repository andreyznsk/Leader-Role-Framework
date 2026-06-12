# Scenario: Семантический поиск через /api/search

**service:** JavaRagService
**port:** 8081
**priority:** HIGH
**depends_on:** postgres, opensearch, ollama

## Описание
Проиндексировать два документа с разным содержимым.
Выполнить семантические запросы — проверить что правильный документ
возвращается первым по релевантности.
Проверить top_k параметр. Проверить поиск на кириллице.

## Preconditions
- JavaRagService запущен на :8081
- Ollama запущена с моделью multilingual-e5-large

## Переменные окружения
```bash
source JavaRagService/test_e2e/env.sh
```

## Steps

### Step 1 — Создать документ про релизы
```bash
mkdir -p rag-inbox
cat > rag-inbox/e2e-release-process.md <<'EOF'
---
type: ADR
title: Release Process
status: active
updated: 2026-06-12
---
# ADR-RELEASE: Release Process

## Статус
Active

## Контекст
Перед релизом необходимо создать release branch от develop.
Тестировщик проводит регрессионное тестирование на staging окружении.
После успешного smoke-теста техлид согласует деплой в production.

## Решение
При возникновении проблем выполняется откат через Jenkins pipeline.
Команда rollback: kubectl rollout undo deployment/payments-api
Время отката: обычно 2-3 минуты.

## Последствия
Если rollback не помог — эскалация к дежурному инженеру инфраструктуры.
EOF
echo "Release doc created"
```
**Expected:** файл создан

### Step 2 — Создать документ про онбординг
```bash
cat > rag-inbox/e2e-onboarding.md <<'EOF'
---
type: ADR
title: Team Onboarding Guide
status: active
updated: 2026-06-12
---
# ADR-ONBOARD: Team Onboarding Guide

## Статус
Active

## Контекст
Новый сотрудник получает доступ к Jira, Confluence и Bitbucket.
Настраивает VPN и локальное окружение разработки.
Знакомится с архитектурой системы через ADR-документы.

## Решение
Проводится встреча с техлидом для погружения в контекст команды.
Назначается ментор из числа опытных разработчиков.
Первая задача — небольшой bugfix для знакомства с кодовой базой.

## Последствия
Запрос доступов через Service Desk тикет категории "Новый сотрудник".
EOF
echo "Onboarding doc created"
```
**Expected:** файл создан

### Step 3 — Проиндексировать оба документа
```bash
R1=$(curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/e2e-release-process.md"}')
R2=$(curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/e2e-onboarding.md"}')
echo "Release: $(echo $R1 | jq -r '.chunksAdded // .status')"
echo "Onboarding: $(echo $R2 | jq -r '.chunksAdded // .status')"
```
**Expected:** оба вернули `chunksAdded` > 0

### Step 4 — Поиск по теме релиза — находит release документ
```bash
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"как проходит релиз и деплой в production","top_k":3}' \
  | jq '[.[] | {source, score, text: .text[:100]}]'
```
**Expected:** HTTP 200, первый результат содержит `source` с `e2e-release-process`

### Step 5 — Поиск по теме онбординга — находит onboarding документ
```bash
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"как настроить окружение новому сотруднику","top_k":3}' \
  | jq '[.[] | {source, text: .text[:100]}]'
```
**Expected:** HTTP 200, первый результат содержит `source` с `e2e-onboarding`

### Step 6 — Поиск через /api/search для rollback
```bash
curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"rollback при инциденте","top_k":2}' \
  | jq '.'
```
**Expected:** HTTP 200, результаты содержат `source` с `e2e-release-process` (там есть rollback)

### Step 7 — top_k параметр работает корректно
```bash
COUNT=$(curl -s --max-time 15 -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"команда разработка","top_k":2}' \
  | jq 'length')
echo "Results count: $COUNT"
```
**Expected:** результат `<= 2`

## Cleanup
```bash
rm -f rag-inbox/e2e-release-process.md rag-inbox/e2e-onboarding.md
echo "Cleanup: test documents removed"
```
