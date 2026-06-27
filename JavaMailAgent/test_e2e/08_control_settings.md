# Scenario: MailAgent control settings API

**service:** JavaMailAgent  
**ports:** 8080  
**priority:** HIGH  
**depends_on:** postgres, maildev

## Preconditions
- JavaMailAgent запущен на `http://localhost:8080`
- `jq` установлен
- лог пишется в `logs/mail-agent.log`

## Steps

### Step 1 — Проверить descriptor настроек
```bash
curl -s http://localhost:8080/api/control/settings | jq '{pluginCode, hasEnabled: (.settings.enabled != null), protocolOptions: .settings.protocol.options}'
```
**Expected:** `pluginCode=mail`, `hasEnabled=true`, в `protocolOptions` есть `maildev`, `imap`, `ews`

### Step 2 — Выключить polling без остановки JVM
```bash
curl -s -X PUT http://localhost:8080/api/control/settings \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "enabled": "false"
    }
  }' | jq '{pluginCode, status, applied, ignored}'
```
**Expected:** `status=APPLIED`, `applied.enabled=false`

### Step 3 — Проверить runtime status после выключения
```bash
curl -s http://localhost:8080/api/control/status | jq '{pluginCode, enabled, polling, protocol, configVersion}'
```
**Expected:** `enabled=false`, `polling=false`

### Step 4 — Включить polling и передать secret
```bash
curl -s -X PUT http://localhost:8080/api/control/settings \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "enabled": "true",
      "password": "plain-value-only-on-write"
    }
  }' | jq '{status, applied, ignored}'
```
**Expected:** `status=APPLIED`, пароль отсутствует в `applied`, в `ignored.password` есть сообщение про hidden secret

### Step 5 — Проверить audit
```bash
curl -s http://localhost:8080/api/control/audit | jq '.[0]'
```
**Expected:** есть запись со `status=APPLIED`, `changedKeys` содержит `enabled` или `password`

### Step 6 — Проверить логи на отсутствие plain password
```bash
grep -n "plain-value-only-on-write" logs/mail-agent.log
```
**Expected:** вывод пустой
