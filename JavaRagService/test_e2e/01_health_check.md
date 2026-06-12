# Scenario: Health Check

**service:** JavaRagService
**port:** 8081
**priority:** CRITICAL
**depends_on:** postgres, opensearch, ollama

## Переменные окружения
```bash
source JavaRagService/test_e2e/env.sh
echo "OpenSearch: $OPENSEARCH_URL | Ollama: $OLLAMA_URL"
```

## Steps

### Step 1 — JavaRagService health
```bash
curl -s -o /dev/null -w "%{http_code}" --max-time 10 http://localhost:8081/actuator/health
```
**Expected:** HTTP 200

### Step 2 — OpenSearch доступен (cluster health)
```bash
curl -s --max-time 10 "$OPENSEARCH_URL/_cluster/health" | jq '{cluster_name, status}'
```
**Expected:** HTTP 200, поле `status` = `green` или `yellow`

> Примечание: OpenSearch 3.x убрал поле `status` из корневого `GET /` — используем `/_cluster/health`

### Step 3 — Ollama доступен и модель загружена
```bash
curl -s --max-time 10 "$OLLAMA_URL/api/tags" | jq '[.models[].name]'
```
**Expected:** HTTP 200, список содержит строку с `multilingual-e5-large`

### Step 4 — PostgreSQL: таблица indexed_documents существует
```bash
docker exec leader-postgres psql -U rag_user -d leader_framework \
  -c "SELECT COUNT(*) FROM rag.indexed_documents;"
```
**Expected:** команда выполнена без ошибки, возвращает число >= 0

> Примечание: `psql` не установлен локально — используем `docker exec leader-postgres`

### Step 5 — OpenSearch: индекс rag-knowledge существует или создаётся при старте
```bash
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$OPENSEARCH_URL/rag-knowledge")
echo "HTTP: $HTTP_CODE"
```
**Expected:** HTTP 200 (индекс существует) или HTTP 404 (будет создан при первой индексации — тоже OK)

### Step 6 — rag_status возвращает список документов
```bash
curl -s --max-time 10 http://localhost:8081/api/rag/status
```
**Expected:** HTTP 200, тело является валидным JSON-массивом

## Cleanup
# ничего не требуется
