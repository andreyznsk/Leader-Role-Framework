# Scenario: Universal plugin control UI and proxy API

**service:** JavaMemoryService  
**ports:** 8082  
**priority:** HIGH  
**depends_on:** JavaMailAgent, JavaRagService

## Preconditions
- `JavaMemoryService` запущен на `http://localhost:8082`
- `JavaMailAgent` запущен на `http://localhost:8080`
- `JavaRagService` запущен на `http://localhost:8081`
- `jq` установлен

## Steps

### Step 1 — Проверить список control plugins
```bash
curl -s http://localhost:8082/api/settings/control/plugins | jq '[.[].code]'
```
**Expected:** массив содержит `mail` и `rag`

### Step 2 — Прочитать descriptor MailAgent
```bash
curl -s http://localhost:8082/api/settings/control/plugins/mail/settings | jq '{pluginCode, protocolType: .settings.protocol.type}'
```
**Expected:** `pluginCode=mail`, `protocolType=select`

### Step 3 — Прочитать descriptor RAG Service
```bash
curl -s http://localhost:8082/api/settings/control/plugins/rag/settings | jq '{pluginCode, enabledType: .settings.enabled.type}'
```
**Expected:** `pluginCode=rag`, `enabledType=boolean`

### Step 4 — Применить тестовую настройку MailAgent
```bash
curl -s -X PUT http://localhost:8082/api/settings/control/plugins/mail/settings \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "enabled": "false"
    }
  }' | jq '{pluginCode, status, applied}'
```
**Expected:** `status=APPLIED`, `applied.enabled=false`

### Step 5 — Применить тестовую настройку RAG Service
```bash
curl -s -X PUT http://localhost:8082/api/settings/control/plugins/rag/settings \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "enabled": "true"
    }
  }' | jq '{pluginCode, status, applied}'
```
**Expected:** `status=APPLIED`

### Step 6 — Проверить audit по plugin proxy
```bash
curl -s http://localhost:8082/api/settings/control/plugins/mail/audit | jq '.[0]'
```
**Expected:** есть запись со `status=APPLIED`

### Step 7 — Проверить универсальный UI
```bash
curl -s http://localhost:8082/ui/settings | grep -E "Mail Agent|RAG Service|Scan interval seconds|Folders exclude"
```
**Expected:** страница содержит блоки для `Mail Agent` и `RAG Service`

### Step 8 — Проверить понятную ошибку при недоступном plugin
```bash
curl -i -s http://localhost:8082/api/settings/control/plugins/rag/settings
```
**Expected:** при недоступном `JavaRagService` ответ не валит `JavaMemoryService`, а возвращает понятную ошибку уровня proxy
