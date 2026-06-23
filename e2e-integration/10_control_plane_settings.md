# Scenario: Control Plane — Universal Plugin Settings & Connection Test

**service:** JavaMailAgent + JavaRagService + JavaMemoryService
**ports:** 8080, 8081, 8082
**priority:** HIGH
**depends_on:** maildev
**profile:** local, `mail.protocol=maildev`

## Описание

Проверяет универсальный механизм получения и изменения настроек плагин-сервисов.
Каждый сервис (Mail Agent, RAG Service, ...) предоставляет одинаковый `/api/control` API.
Memory Service выступает прокси, агрегируя настройки всех плагинов через `/api/settings/control/plugins/{code}`.

Проверяемые инварианты:
- Настройки возвращаются при **первом** обращении без предварительной инициализации
- Изменение настроек применяется без перезапуска JVM
- `POST /api/control/test-connection` возвращает **200** при успехе и **500** при ошибке подключения
- Memory Service proxy правильно пробрасывает статус подключения

## Preconditions

- JavaMailAgent запущен на :8080 с `mail.protocol=maildev`
- JavaRagService запущен на :8081
- JavaMemoryService запущен на :8082
- Maildev доступен через Docker bridge (172.80.2.1:18080)

## Environment

```bash
source e2e-integration/env.sh
```

---

## Steps

### Step 1 — Проверить доступность всех трёх сервисов

```bash
MA_H=$(curl -s -o /dev/null -w "%{http_code}" $MA_URL/actuator/health)
RAG_H=$(curl -s -o /dev/null -w "%{http_code}" $RAG_URL/actuator/health)
MS_H=$(curl -s -o /dev/null -w "%{http_code}" $MS_URL/actuator/health)
echo "MailAgent: $MA_H | RagService: $RAG_H | MemoryService: $MS_H"
```

**Expected:** все три 200

---

### Step 2 — Настройки Mail Agent при первом обращении (прямой вызов)

```bash
MA_SETTINGS=$(curl -s $MA_URL/api/control/settings)
echo "$MA_SETTINGS" | jq '{pluginCode, pluginName, version, settingKeys: (.settings | keys)}'
```

**Expected:**
- `pluginCode = "mail"`
- `pluginName = "Mail Agent"`
- `settings` содержит ключи: `enabled`, `protocol`, `login`, `password`, `host`, `port`, `pollIntervalSeconds`
- `version >= 1`

```bash
MA_PLUGIN_CODE=$(echo "$MA_SETTINGS" | jq -r '.pluginCode')
MA_PROTOCOL=$(echo "$MA_SETTINGS" | jq -r '.settings.protocol.value')
MA_ENABLED=$(echo "$MA_SETTINGS" | jq -r '.settings.enabled.value')
echo "pluginCode=$MA_PLUGIN_CODE | protocol=$MA_PROTOCOL | enabled=$MA_ENABLED"
[ "$MA_PLUGIN_CODE" = "mail" ] && echo "✅ pluginCode OK" || echo "❌ pluginCode FAIL: $MA_PLUGIN_CODE"
[ -n "$MA_PROTOCOL" ] && echo "✅ protocol present" || echo "❌ protocol FAIL"
```

**Extract:** `$MA_PROTOCOL` (оригинальный протокол для восстановления)

---

### Step 3 — Настройки RAG Service при первом обращении (прямой вызов)

```bash
RAG_SETTINGS=$(curl -s $RAG_URL/api/control/settings)
echo "$RAG_SETTINGS" | jq '{pluginCode, pluginName, version, settingKeys: (.settings | keys)}'
```

**Expected:**
- `pluginCode = "rag"`
- `pluginName = "RAG Service"`
- `settings` содержит ключи: `enabled`, `schedulerEnabled`, `ragInboxPath`, `topK`

```bash
RAG_PLUGIN_CODE=$(echo "$RAG_SETTINGS" | jq -r '.pluginCode')
[ "$RAG_PLUGIN_CODE" = "rag" ] && echo "✅ RAG pluginCode OK" || echo "❌ RAG pluginCode FAIL: $RAG_PLUGIN_CODE"
```

---

### Step 4 — Список плагин-контроллеров через Memory Service

```bash
MS_PLUGINS=$(curl -s $MS_URL/api/settings/control/plugins)
echo "$MS_PLUGINS" | jq '[.[] | {code, name, baseUrl, healthStatus}]'
```

**Expected:**
- Список содержит `mail` и `rag`
- Оба плагина доступны (`healthStatus` не `DOWN`)

```bash
HAS_MAIL=$(echo "$MS_PLUGINS" | jq '[.[] | select(.code == "mail")] | length')
HAS_RAG=$(echo "$MS_PLUGINS" | jq '[.[] | select(.code == "rag")] | length')
[ "$HAS_MAIL" -ge 1 ] && echo "✅ mail plugin listed" || echo "❌ mail plugin missing"
[ "$HAS_RAG" -ge 1 ] && echo "✅ rag plugin listed" || echo "❌ rag plugin missing"
```

---

### Step 5 — Настройки mail через Memory Service proxy

```bash
MS_MAIL_SETTINGS=$(curl -s $MS_URL/api/settings/control/plugins/mail/settings)
echo "$MS_MAIL_SETTINGS" | jq '{pluginCode, version, settingKeys: (.settings | keys)}'
```

**Expected:** та же структура, что и прямой вызов к MA

```bash
MS_MAIL_PLUGIN_CODE=$(echo "$MS_MAIL_SETTINGS" | jq -r '.pluginCode')
[ "$MS_MAIL_PLUGIN_CODE" = "mail" ] && echo "✅ proxy mail pluginCode OK" || echo "❌ proxy mail pluginCode FAIL"
```

---

### Step 6 — Настройки RAG через Memory Service proxy

```bash
MS_RAG_SETTINGS=$(curl -s $MS_URL/api/settings/control/plugins/rag/settings)
echo "$MS_RAG_SETTINGS" | jq '{pluginCode, version, settingKeys: (.settings | keys)}'
```

**Expected:** `pluginCode = "rag"`, структура совпадает с прямым вызовом

```bash
MS_RAG_PLUGIN_CODE=$(echo "$MS_RAG_SETTINGS" | jq -r '.pluginCode')
[ "$MS_RAG_PLUGIN_CODE" = "rag" ] && echo "✅ proxy rag pluginCode OK" || echo "❌ proxy rag pluginCode FAIL"
```

---

### Step 7 — testConnection — успех (maildev доступен)

```bash
TC_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST $MA_URL/api/control/test-connection)
TC_BODY=$(curl -s -X POST $MA_URL/api/control/test-connection)
echo "HTTP: $TC_CODE | body: $TC_BODY"
echo "$TC_BODY" | jq '{success, message, target}'
```

**Expected:** HTTP **200**, `success = true`

```bash
TC_SUCCESS=$(echo "$TC_BODY" | jq -r '.success')
[ "$TC_CODE" = "200" ] && echo "✅ HTTP 200 OK" || echo "❌ HTTP FAIL: $TC_CODE"
[ "$TC_SUCCESS" = "true" ] && echo "✅ success=true" || echo "❌ success FAIL: $TC_SUCCESS"
```

---

### Step 8 — testConnection через Memory Service proxy — успех

```bash
MS_TC_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST $MS_URL/api/settings/plugins/mail/test-connection)
MS_TC_BODY=$(curl -s -X POST $MS_URL/api/settings/plugins/mail/test-connection)
echo "HTTP: $MS_TC_CODE | body: $MS_TC_BODY"
```

**Expected:** HTTP **200**, `success = true`

```bash
MS_TC_SUCCESS=$(echo "$MS_TC_BODY" | jq -r '.success')
[ "$MS_TC_CODE" = "200" ] && echo "✅ MS proxy HTTP 200 OK" || echo "❌ MS proxy HTTP FAIL: $MS_TC_CODE"
[ "$MS_TC_SUCCESS" = "true" ] && echo "✅ MS proxy success=true" || echo "❌ MS proxy success FAIL"
```

---

### Step 9 — Сохранить оригинальный протокол для восстановления

```bash
ORIG_PROTOCOL=$(curl -s $MA_URL/api/control/settings | jq -r '.settings.protocol.value')
echo "Original protocol: $ORIG_PROTOCOL"
```

**Extract:** `$ORIG_PROTOCOL`

---

### Step 10 — Обновить настройки mail: переключиться на EWS с неверным URL

Переключаем на EWS-протокол с несуществующим сервером, чтобы смоделировать ошибку подключения.

```bash
UPDATE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT $MA_URL/api/control/settings \
  -H "Content-Type: application/json" \
  -d '{"settings":{"protocol":"ews","serverUrl":"http://localhost:19999/EWS/Exchange.asmx","login":"test@bad.host"}}')
UPDATE_BODY=$(curl -s -X PUT $MA_URL/api/control/settings \
  -H "Content-Type: application/json" \
  -d '{"settings":{"protocol":"ews","serverUrl":"http://localhost:19999/EWS/Exchange.asmx","login":"test@bad.host"}}')
echo "HTTP: $UPDATE_CODE | status: $(echo "$UPDATE_BODY" | jq -r '.status')"
```

**Expected:** HTTP **200**, `status = "APPLIED"`

```bash
UPDATE_STATUS=$(echo "$UPDATE_BODY" | jq -r '.status')
[ "$UPDATE_CODE" = "200" ] && echo "✅ settings update HTTP 200" || echo "❌ settings update FAIL: $UPDATE_CODE"
[ "$UPDATE_STATUS" = "APPLIED" ] && echo "✅ status=APPLIED" || echo "❌ status FAIL: $UPDATE_STATUS"
```

---

### Step 11 — Проверить что новые настройки применились

```bash
NEW_SETTINGS=$(curl -s $MA_URL/api/control/settings)
NEW_PROTOCOL=$(echo "$NEW_SETTINGS" | jq -r '.settings.protocol.value')
NEW_URL=$(echo "$NEW_SETTINGS" | jq -r '.settings.serverUrl.value')
echo "protocol=$NEW_PROTOCOL | serverUrl=$NEW_URL"
```

**Expected:** `protocol = "ews"`, `serverUrl = "http://localhost:19999/EWS/Exchange.asmx"`

```bash
[ "$NEW_PROTOCOL" = "ews" ] && echo "✅ protocol=ews applied" || echo "❌ protocol FAIL: $NEW_PROTOCOL"
```

---

### Step 12 — testConnection с неверными настройками — ошибка подключения

> Сервер на localhost:19999 не существует → EWS client вернёт connection refused.

```bash
FAIL_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST $MA_URL/api/control/test-connection)
FAIL_BODY=$(curl -s -X POST $MA_URL/api/control/test-connection)
echo "HTTP: $FAIL_CODE"
echo "$FAIL_BODY" | jq '{success, message, target}'
```

**Expected:** HTTP **500**, `success = false`

```bash
FAIL_SUCCESS=$(echo "$FAIL_BODY" | jq -r '.success')
[ "$FAIL_CODE" = "500" ] && echo "✅ HTTP 500 returned on failure" || echo "❌ HTTP FAIL: expected 500, got $FAIL_CODE"
[ "$FAIL_SUCCESS" = "false" ] && echo "✅ success=false" || echo "❌ success FAIL: $FAIL_SUCCESS"
```

---

### Step 13 — testConnection через MS proxy с неверными настройками — ошибка

```bash
MS_FAIL_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST $MS_URL/api/settings/plugins/mail/test-connection)
MS_FAIL_BODY=$(curl -s -X POST $MS_URL/api/settings/plugins/mail/test-connection)
echo "HTTP: $MS_FAIL_CODE"
echo "$MS_FAIL_BODY" | jq '{success, message}'
```

**Expected:** HTTP **500**, `success = false`

```bash
MS_FAIL_SUCCESS=$(echo "$MS_FAIL_BODY" | jq -r '.success')
[ "$MS_FAIL_CODE" = "500" ] && echo "✅ MS proxy HTTP 500 on failure" || echo "❌ MS proxy FAIL: expected 500, got $MS_FAIL_CODE"
[ "$MS_FAIL_SUCCESS" = "false" ] && echo "✅ MS proxy success=false" || echo "❌ MS proxy success FAIL"
```

---

### Step 14 — Восстановить оригинальные настройки

```bash
RESTORE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT $MA_URL/api/control/settings \
  -H "Content-Type: application/json" \
  -d "{\"settings\":{\"protocol\":\"$ORIG_PROTOCOL\"}}")
echo "Restore HTTP: $RESTORE_CODE"
```

**Expected:** HTTP **200**

```bash
RESTORED_PROTOCOL=$(curl -s $MA_URL/api/control/settings | jq -r '.settings.protocol.value')
echo "Restored protocol: $RESTORED_PROTOCOL"
[ "$RESTORED_PROTOCOL" = "$ORIG_PROTOCOL" ] && echo "✅ protocol restored to $ORIG_PROTOCOL" || echo "❌ restore FAIL: $RESTORED_PROTOCOL"
```

---

### Step 15 — testConnection после восстановления — снова успех

```bash
OK_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST $MA_URL/api/control/test-connection)
OK_BODY=$(curl -s -X POST $MA_URL/api/control/test-connection)
echo "HTTP: $OK_CODE"
echo "$OK_BODY" | jq '{success, message, target}'
```

**Expected:** HTTP **200**, `success = true`

```bash
OK_SUCCESS=$(echo "$OK_BODY" | jq -r '.success')
[ "$OK_CODE" = "200" ] && echo "✅ HTTP 200 after restore" || echo "❌ HTTP FAIL: $OK_CODE"
[ "$OK_SUCCESS" = "true" ] && echo "✅ success=true after restore" || echo "❌ success FAIL"
```

---

### Step 16 — Статус Mail Agent

```bash
curl -s $MA_URL/api/control/status | jq '{pluginCode, status, enabled, polling, protocol, configVersion}'
```

**Expected:**
- `pluginCode = "mail"`
- `status = "UP"`
- `enabled = true`
- `protocol = "maildev"` (после восстановления)
- `configVersion >= 3` (минимум 3 изменения версии за тест)

---

### Step 17 — Аудит изменений Mail Agent

```bash
AUDIT=$(curl -s $MA_URL/api/control/audit)
AUDIT_COUNT=$(echo "$AUDIT" | jq 'length')
echo "Audit entries: $AUDIT_COUNT"
echo "$AUDIT" | jq '[.[] | {appliedAt, status, changedKeys, message}]'
```

**Expected:**
- `length >= 2` (хотя бы 2 записи: переключение на EWS + восстановление)
- каждая запись содержит `appliedAt`, `status`, `changedKeys`

```bash
[ "$AUDIT_COUNT" -ge 2 ] && echo "✅ audit has $AUDIT_COUNT entries" || echo "❌ audit entries FAIL: $AUDIT_COUNT"
```

---

### Step 18 — Обновить настройки RAG через Memory Service proxy

```bash
RAG_UPDATE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT \
  $MS_URL/api/settings/control/plugins/rag/settings \
  -H "Content-Type: application/json" \
  -d '{"settings":{"enabled":"true"}}')
RAG_UPDATE_BODY=$(curl -s -X PUT \
  $MS_URL/api/settings/control/plugins/rag/settings \
  -H "Content-Type: application/json" \
  -d '{"settings":{"enabled":"true"}}')
echo "HTTP: $RAG_UPDATE_CODE | status: $(echo "$RAG_UPDATE_BODY" | jq -r '.status')"
```

**Expected:** HTTP **200**, `status = "APPLIED"`

```bash
RAG_STATUS=$(echo "$RAG_UPDATE_BODY" | jq -r '.status')
[ "$RAG_UPDATE_CODE" = "200" ] && echo "✅ RAG settings update OK" || echo "❌ RAG update FAIL: $RAG_UPDATE_CODE"
[ "$RAG_STATUS" = "APPLIED" ] && echo "✅ RAG status=APPLIED" || echo "❌ RAG status FAIL: $RAG_STATUS"
```

---

### Step 19 — UI: страница настроек открывается

```bash
UI_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$MS_URL/ui/settings")
echo "Settings UI HTTP: $UI_CODE"
```

**Expected:** HTTP **200**

```bash
[ "$UI_CODE" = "200" ] && echo "✅ Settings UI page loads" || echo "❌ UI FAIL: $UI_CODE"
```

---

### Step 20 — UI: Bootstrap JS подключён (кнопка сворачивания работает)

Проверяем HTML страницы — без Bootstrap JS кнопка `▾ Settings` не будет сворачивать/разворачивать панель.

```bash
UI_HTML=$(curl -s "$MS_URL/ui/settings")

BOOTSTRAP_JS=$(echo "$UI_HTML" | grep -c "bootstrap.bundle.min.js")
COLLAPSE_TOGGLE=$(echo "$UI_HTML" | grep -c 'data-bs-toggle="collapse"')
COLLAPSE_DIV=$(echo "$UI_HTML" | grep -c 'class="collapse')

echo "bootstrap.bundle.min.js found: $BOOTSTRAP_JS"
echo "data-bs-toggle=collapse elements: $COLLAPSE_TOGGLE"
echo "collapse divs: $COLLAPSE_DIV"
```

**Expected:**
- `bootstrap.bundle.min.js` найден в HTML (`>= 1`)
- `data-bs-toggle="collapse"` присутствует (`>= 1`)
- `class="collapse` присутствует (`>= 1`)

```bash
[ "$BOOTSTRAP_JS" -ge 1 ] && echo "✅ Bootstrap JS included" || echo "❌ Bootstrap JS MISSING — collapse button will not work"
[ "$COLLAPSE_TOGGLE" -ge 1 ] && echo "✅ collapse toggle present" || echo "❌ collapse toggle MISSING"
[ "$COLLAPSE_DIV" -ge 1 ] && echo "✅ collapse divs present" || echo "❌ collapse divs MISSING"
```

---

### Step 21 — UI: поля настроек отрендерены сервером (данные без JS)

Проверяем, что сервер рендерит значения в HTML без необходимости клиентских fetch-запросов.

```bash
UI_HTML=$(curl -s "$MS_URL/ui/settings")

PROTOCOL_IN_HTML=$(echo "$UI_HTML" | grep -c 'selected.*maildev\|value="maildev"')
PLUGIN_BODY=$(echo "$UI_HTML" | grep -c 'id="plugin-body-mail"')
FORM_PRESENT=$(echo "$UI_HTML" | grep -c 'data-plugin-code="mail"')

echo "maildev in HTML: $PROTOCOL_IN_HTML"
echo "plugin-body-mail div: $PLUGIN_BODY"
echo "plugin form: $FORM_PRESENT"
```

**Expected:** все поля присутствуют в HTML до запуска JS

```bash
[ "$PLUGIN_BODY" -ge 1 ] && echo "✅ plugin body div rendered" || echo "❌ plugin body MISSING"
[ "$FORM_PRESENT" -ge 1 ] && echo "✅ plugin form rendered server-side" || echo "❌ form MISSING"
```

---

## Summary

| Check | Expected |
|-------|----------|
| MA settings on first call | `pluginCode=mail`, все поля присутствуют |
| RAG settings on first call | `pluginCode=rag`, все поля присутствуют |
| MS proxy — list plugins | содержит `mail` и `rag` |
| MS proxy — mail settings | та же структура, что прямой вызов |
| testConnection success (MA direct) | HTTP 200, `success=true` |
| testConnection success (MS proxy) | HTTP 200, `success=true` |
| settings update — EWS bad URL | HTTP 200, `status=APPLIED` |
| testConnection failure (MA direct) | HTTP **500**, `success=false` |
| testConnection failure (MS proxy) | HTTP **500**, `success=false` |
| settings restore | HTTP 200, протокол возвращён |
| testConnection after restore | HTTP 200, `success=true` |
| audit trail | не менее 2 записей |
| RAG settings via MS proxy | HTTP 200, `status=APPLIED` |
| UI page loads | HTTP 200 |
| Bootstrap JS в HTML | `bootstrap.bundle.min.js` найден |
| Collapse toggle в HTML | `data-bs-toggle="collapse"` найден |
| Форма отрендерена сервером | `plugin-body-mail` и `data-plugin-code` в HTML |

## Cleanup

```bash
# Принудительно восстановить maildev протокол если тест прервался
curl -s -X PUT $MA_URL/api/control/settings \
  -H "Content-Type: application/json" \
  -d '{"settings":{"protocol":"maildev"}}' > /dev/null
echo "IT-10 cleanup done"
```
