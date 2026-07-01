# Scenario: UI smoke — left sidebar navigation

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0 (CR-MEM-022)

## Описание

Проверить, что после перехода на левый sidebar (CR-MEM-022) все основные страницы
MemoryService UI продолжают открываться по старым URL и содержат общий
navigation marker `<nav data-testid="leaderos-sidebar">`.

## Preconditions

- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432

## Steps

### Step 1 — Основные страницы возвращают HTTP 200 и содержат sidebar marker

```bash
for p in today notes captures knowledge settings stats agent-workspace people risks incidents intake presentation search; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8082/ui/$p")
  MARKER=$(curl -s "http://localhost:8082/ui/$p" | grep -c 'data-testid="leaderos-sidebar"')
  if [ "$CODE" != "200" ] || [ "$MARKER" -lt 1 ]; then
    echo "FAIL: /ui/$p code=$CODE marker=$MARKER"
    exit 1
  fi
done
echo "all pages OK"
```
**Expected:** вывод `all pages OK`

### Step 2 — Верхнее глобальное меню больше не используется

```bash
HTML=$(curl -s http://localhost:8082/ui/today)
echo "$HTML" | grep -qv 'class="los-navbar"' && echo "no top navbar OK"
```
**Expected:** вывод `no top navbar OK` (класс `los-navbar` удалён, используется `los-sidebar`)

### Step 3 — /ui/search рендерится напрямую, без редиректа в Agent Workspace (CR-MEM-022 addendum)

```bash
curl -s -I http://localhost:8082/ui/search | tr -d '\r'
```
**Expected:** статус `200` (не `302` — Search больше не релоцирован в `/ui/agent-workspace?tab=search`)

### Step 4 — Agent Workspace больше не содержит Search-вкладку

```bash
HTML=$(curl -s http://localhost:8082/ui/agent-workspace)
echo "$HTML" | grep -qv 'tab=search' && echo "no search tab in agent-workspace OK"
```
**Expected:** вывод `no search tab in agent-workspace OK`

## Cleanup

```bash
echo "No cleanup required"
```
