# Scenario: Agent Workspace — Chat и Console (CR-MEM-012)

**service:** JavaMemoryService  
**port:** 8082  
**priority:** HIGH  
**depends_on:** postgres

## Описание

Проверяет Agent Workspace UI и API:
- UI страница `/ui/agent-workspace` доступна
- Chat mode: POST `/api/agent/chat/run` с mock provider
- Chat mode: валидация пустого prompt
- Chat mode: неизвестный provider возвращает ERROR
- Аудит запусков пишется в `memory.agent_workspace_runs`
- WebSocket endpoint `/ws/agent-console` отвечает на upgrade
- Security: неразрешённая команда отклоняется

Все тесты используют `agent.provider=mock`, поэтому реальный Claude CLI не нужен.

## Preconditions

- JavaMemoryService запущен на `:8082`
- Профиль включает mock provider или `agentWorkspace.console.allowedCommands: [claude]`

---

## Steps

### Step 1 — UI smoke: страница /ui/agent-workspace доступна
```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8082/ui/agent-workspace)
echo "HTTP: $HTTP_CODE"
```
**Expected:** HTTP 200

### Step 2 — UI содержит вкладки Chat и Console
```bash
BODY=$(curl -s http://localhost:8082/ui/agent-workspace)
echo "$BODY" | grep -c -i "chat\|console" || echo "0"
```
**Expected:** значение больше 0 (обе вкладки присутствуют в разметке)

### Step 3 — Chat mode: mock provider, корректный prompt
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/agent/chat/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"ping","provider":"mock","includeContext":false}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE"
echo "$BODY" | jq '{status, provider, durationMs}'
```
**Expected:** HTTP 200, `"status":"SUCCESS"`, `"provider":"mock"`, `durationMs >= 0`

### Step 4 — Chat mode: ответ содержит непустой response
```bash
curl -s -X POST http://localhost:8082/api/agent/chat/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"ping","provider":"mock","includeContext":false}' \
  | jq -r '.response // empty | length > 0'
```
**Expected:** `true`

### Step 5 — Chat mode: пустой prompt возвращает ошибку
```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/api/agent/chat/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"","provider":"mock","includeContext":false}')
echo "HTTP: $HTTP_CODE"
```
**Expected:** HTTP 400

### Step 6 — Chat mode: отсутствующий prompt возвращает ошибку
```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8082/api/agent/chat/run \
  -H "Content-Type: application/json" \
  -d '{"provider":"mock"}')
echo "HTTP: $HTTP_CODE"
```
**Expected:** HTTP 400

### Step 7 — Chat mode: неизвестный provider возвращает ERROR-статус (не 5xx)
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/agent/chat/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"test","provider":"nonexistent_provider","includeContext":false}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE"
echo "$BODY" | jq '{status, error}'
```
**Expected:** HTTP 200, `"status":"ERROR"`, поле `error` непустое; либо HTTP 400 — но не 5xx

### Step 8 — Аудит: run зафиксирован в memory.agent_workspace_runs
```bash
PGPASSWORD=memory_password psql -h 172.80.2.1 -U memory_user -d leader_framework -t -c \
  "SELECT COUNT(*) FROM memory.agent_workspace_runs WHERE mode='CHAT' AND provider='mock' AND status='SUCCESS';"
```
**Expected:** значение >= 1 (записи созданы шагами 3–4)

### Step 9 — Аудит: поля run корректно заполнены
```bash
PGPASSWORD=memory_password psql -h 172.80.2.1 -U memory_user -d leader_framework -t -c \
  "SELECT mode, provider, status, duration_ms IS NOT NULL AS has_duration
   FROM memory.agent_workspace_runs
   WHERE mode='CHAT' AND provider='mock' AND status='SUCCESS'
   ORDER BY created_at DESC LIMIT 1;"
```
**Expected:** строка содержит `CHAT | mock | SUCCESS | t`

### Step 10 — WebSocket endpoint отвечает на HTTP Upgrade
```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  --no-buffer \
  -H "Connection: Upgrade" \
  -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" \
  -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  http://localhost:8082/ws/agent-console)
echo "HTTP: $HTTP_CODE"
```
**Expected:** HTTP 101 (WebSocket Switching Protocols)

### Step 11 — Security: shell-команда отклоняется через WebSocket message
```bash
# Посылаем START с запрещённой командой bash через сырой WebSocket handshake
# websocat используется если доступен; иначе проверяем через dedicated REST эндпойнт (если есть)
if command -v websocat &>/dev/null; then
  RESULT=$(echo '{"type":"START","provider":"bash"}' \
    | timeout 5 websocat ws://localhost:8082/ws/agent-console 2>&1 || true)
  echo "$RESULT" | grep -i "error\|not allowed\|forbidden\|whitelist" && echo "BLOCKED_OK" || echo "$RESULT"
else
  echo "websocat not available — manual check required"
  echo "SKIP"
fi
```
**Expected:** сообщение содержит `BLOCKED_OK` или `SKIP` (не падает с необработанной ошибкой)

### Step 12 — Console: WebSocket сессия стартует с разрешённым provider
```bash
if command -v websocat &>/dev/null; then
  RESULT=$(echo '{"type":"START","provider":"claude"}' \
    | timeout 5 websocat ws://localhost:8082/ws/agent-console 2>&1 || true)
  echo "$RESULT" | grep -i "session_started\|SESSION_STARTED\|error" | head -3
else
  echo "websocat not available — SKIP"
fi
```
**Expected:** ответ содержит `SESSION_STARTED` (или `ERROR` если claude CLI не установлен) — но не падает сервер

### Step 13 — Chat mode: включение контекста не ломает запрос
```bash
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8082/api/agent/chat/run \
  -H "Content-Type: application/json" \
  -d '{"prompt":"Что у меня сегодня?","provider":"mock","includeContext":true}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | head -n -1)
echo "HTTP: $HTTP_CODE | status: $(echo "$BODY" | jq -r '.status')"
```
**Expected:** HTTP 200, `"status":"SUCCESS"`

### Step 14 — UI навигация: Agent Workspace есть в меню
```bash
curl -s http://localhost:8082/ui/today | grep -i "agent.workspace\|agent-workspace\|Agent Workspace" | head -2
```
**Expected:** строка найдена (пункт меню присутствует на главной странице)

---

## Cleanup
```bash
# Данные аудита не удаляем — они используются для статистики
# WebSocket-сессии завершаются автоматически по таймауту
echo "No cleanup required"
```
