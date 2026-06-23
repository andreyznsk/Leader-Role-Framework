# Scenario: RAG control settings API

**service:** JavaRagService  
**port:** 8081  
**priority:** HIGH  
**depends_on:** postgres, opensearch, ollama

## Preconditions
- JavaRagService запущен на `http://localhost:8081`
- `jq` установлен
- `psql` доступен или есть доступ к PostgreSQL контейнеру

## Steps

### Step 1 — Проверить descriptor настроек
```bash
curl -s http://localhost:8081/api/control/settings | jq '{
  pluginCode,
  hasEnabled: (.settings.enabled != null),
  hasSchedulerEnabled: (.settings.schedulerEnabled != null),
  hasInboxPath: (.settings.ragInboxPath != null),
  hasEmbeddingModel: (.settings.embeddingModel != null),
  hasTopK: (.settings.topK != null),
  hasOpenSearchUrl: (.settings.opensearchUrl != null),
  hasValidationEnabled: (.settings.validationEnabled != null)
}'
```
**Expected:** `pluginCode=rag`, все `has* = true`

### Step 2 — Выключить RAG без остановки JVM
```bash
curl -s -X PUT http://localhost:8081/api/control/settings \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "enabled": "false",
      "schedulerEnabled": "false",
      "scanIntervalSeconds": "15",
      "topK": "7",
      "validationEnabled": "false"
    }
  }' | jq '{pluginCode, status, applied, ignored}'
```
**Expected:** `status=APPLIED`, `applied.enabled=false`, `applied.schedulerEnabled=false`, `applied.topK=7`

### Step 3 — Проверить runtime status после выключения
```bash
curl -s http://localhost:8081/api/control/status | jq '{
  pluginCode,
  status,
  enabled,
  schedulerEnabled,
  validationEnabled,
  topK,
  configVersion
}'
```
**Expected:** `status=DISABLED`, `enabled=false`, `schedulerEnabled=false`, `validationEnabled=false`, `topK=7`

### Step 4 — Попытка ручной индексации в disabled режиме
```bash
cat > rag-inbox/control-disabled-test.md <<'EOF'
---
type: glossary
updated: 2026-06-23
---
# Control test
- key: value
EOF

curl -s -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/control-disabled-test.md"}' | jq '.'
```
**Expected:** `status=disabled`

### Step 5 — Включить RAG обратно и сменить модель
```bash
curl -s -X PUT http://localhost:8081/api/control/settings \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "enabled": "true",
      "schedulerEnabled": "true",
      "embeddingModel": "bge-m3",
      "topK": "3",
      "validationEnabled": "true"
    }
  }' | jq '{status, applied}'
```
**Expected:** `status=APPLIED`, `applied.enabled=true`, `applied.embeddingModel=bge-m3`, `applied.topK=3`

### Step 6 — Проверить audit
```bash
curl -s http://localhost:8081/api/control/audit | jq '.[0]'
```
**Expected:** есть запись со `status=APPLIED`, `changedKeys` содержит `enabled`, `schedulerEnabled`, `embeddingModel` или `topK`

### Step 7 — Проверить запись аудита в БД
```bash
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" \
  -c "SELECT status, message FROM rag.control_settings_audit ORDER BY id DESC LIMIT 3;"
```
**Expected:** есть строки со `status=APPLIED`

## Cleanup
```bash
rm -f rag-inbox/control-disabled-test.md
```
