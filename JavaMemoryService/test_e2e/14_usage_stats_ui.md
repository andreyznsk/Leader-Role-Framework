# Scenario: Usage Statistics — UI

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0

## Описание
Проверить, что страница `/ui/stats` доступна, содержит основные блоки статистики и переключатели периода.

## Preconditions
- JavaMemoryService запущен на :8082

## Steps

### Step 1 — Открыть страницу статистики
```bash
curl -s -o /tmp/memory-stats.html -w "%{http_code}" "http://localhost:8082/ui/stats?period=7d"
```
**Expected:** HTTP code `200`

### Step 2 — Проверить ключевые элементы
```bash
grep -E "Statistics|Статистика" /tmp/memory-stats.html
grep -E "Saved time|Сэкономлено" /tmp/memory-stats.html
grep -E "Today|7 days|30 days|All time" /tmp/memory-stats.html
```
**Expected:** все `grep` находят совпадения

### Step 3 — Проверить default period
```bash
curl -s "http://localhost:8082/ui/stats" | grep "7 days"
```
**Expected:** найден переключатель `7 days`

