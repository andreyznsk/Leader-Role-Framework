# Scenario: UI navigation — Operational Memory and Knowledge Gateway

**service:** JavaMemoryService
**port:** 8082
**priority:** HIGH
**depends_on:** postgres
**version:** 1.0 (CR-MEM-009)

## Описание
Проверить, что UI Memory Service явно разделён на:
- `Operational Notes` для локального Memory storage;
- `RAG Knowledge` для gateway к JavaRagService;
- `/ui/notice` больше не самостоятельный экран, а redirect на canonical RAG filter.

## Preconditions
- JavaMemoryService запущен на :8082
- PostgreSQL доступен на :5432

## Steps

### Step 1 — /ui/notes доступен и показывает Operational Notes
```bash
HTML=$(curl -s http://localhost:8082/ui/notes)
echo "$HTML" | grep -q "Operational Notes" && \
echo "$HTML" | grep -q "Operational Memory" && \
echo "notes title OK"
```
**Expected:** вывод `notes title OK`

### Step 2 — /ui/knowledge доступен и показывает RAG Knowledge
```bash
HTML=$(curl -s http://localhost:8082/ui/knowledge)
echo "$HTML" | grep -q "RAG Knowledge" && \
echo "$HTML" | grep -q "Knowledge Gateway" && \
echo "knowledge title OK"
```
**Expected:** вывод `knowledge title OK`

### Step 3 — /ui/notice делает redirect на RAG filter
```bash
curl -s -I http://localhost:8082/ui/notice | tr -d '\r'
```
**Expected:** статус `302`, заголовок `Location: /ui/knowledge?type=RAG`

### Step 4 — Навигация не путает Notes и RAG Knowledge
```bash
HTML=$(curl -s http://localhost:8082/ui/knowledge)
echo "$HTML" | grep -q "Notes" && \
echo "$HTML" | grep -q "RAG Knowledge" && \
echo "$HTML" | grep -q "RAG Documents" && \
echo "navigation split OK"
```
**Expected:** вывод `navigation split OK`

## Cleanup
```bash
echo "No cleanup required"
```
