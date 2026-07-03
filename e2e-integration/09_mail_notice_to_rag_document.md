# Scenario: Email RAG -> RAG document -> Memory Knowledge UI/API

**service:** JavaMailAgent + JavaRagService + JavaMemoryService
**ports:** 8080, 8081, 8082
**priority:** HIGH
**depends_on:** maildev, postgres, opensearch, ollama
**profile:** ollama
**cr:** CR-ARCH-005

## Описание
Письмо с явным knowledge-смыслом и маркером `RAG` должно пройти полный flow:

`email -> MailAgent -> rag-inbox/mail/.../*.md -> RagService index -> Memory /api/notices -> /ui/knowledge?type=RAG -> edit -> outdated -> reindex -> indexed`

Legacy alias `NOTICE` тоже должен приниматься, но canonical route/type/UI теперь везде `RAG`.

Сценарий рассчитан на запуск сервисов с профилем `ollama`:

```bash
./test-runner/build.sh
./test-runner/start-services.sh --profile ollama
source e2e-integration/env.sh
```

## Preconditions
- `docker compose up -d` уже выполнен
- JavaMailAgent, JavaRagService и JavaMemoryService запущены с `--profile ollama`
- `./test-runner/healthcheck.sh` возвращает OK по инфраструктуре
- модель Ollama и классификация писем доступны локально
- директория `JavaRagService/rag-inbox/mail` существует или может быть создана сервисом

## Steps

### Step 1 — Все три сервиса живы
```bash
MA=$(curl --max-time 10 -s -o /dev/null -w "%{http_code}" $MA_URL/actuator/health)
RAG=$(curl --max-time 10 -s -o /dev/null -w "%{http_code}" $RAG_URL/actuator/health)
MS=$(curl --max-time 10 -s -o /dev/null -w "%{http_code}" $MS_URL/actuator/health)
echo "MailAgent: $MA | RagService: $RAG | MemoryService: $MS"
```
**Expected:** все три кода = `200`

### Step 2 — Очистить Maildev и снять baseline
```bash
curl --max-time 10 -s -X DELETE $MAILDEV_URL/email/all > /dev/null
DOCS_BEFORE=$(curl --max-time 10 -s $RAG_URL/api/rag/status | jq 'length')
RAG_DOCS_BEFORE=$(curl --max-time 10 -s $MS_URL/api/notices | jq 'length')
LOG_BEFORE=$(wc -l < logs/JavaMailAgent.log)
RUN_ID="it09-$(date +%s)"
echo "DOCS_BEFORE=$DOCS_BEFORE | RAG_DOCS_BEFORE=$RAG_DOCS_BEFORE | RUN_ID=$RUN_ID | LOG_BEFORE=$LOG_BEFORE"
```
**Extract:** `$DOCS_BEFORE`, `$RAG_DOCS_BEFORE`, `$RUN_ID`, `$LOG_BEFORE`

### Step 3 — Отправить RAG письмо
```bash
TMPFILE=$(mktemp)
printf "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nSubject: RAG: %s Новый порядок release calendar\r\nFrom: architect@company.ru\r\nTo: me@test.com\r\n\r\nRAG\r\n%s\r\nС сегодняшнего дня backend-релизы согласуются через общий release calendar.\r\nПеред выкладкой нужно проверить зависимости, release notes и окно на smoke.\r\nЭто правило обязательно для всех команд платформы.\r\n" "$RUN_ID" "$RUN_ID" > "$TMPFILE"
curl --max-time 10 -s -o /dev/null -w "SMTP: %{http_code}\n" --url "smtp://$MAILDEV_SMTP" \
  --mail-from "architect@company.ru" --mail-rcpt "me@test.com" --upload-file "$TMPFILE"
rm "$TMPFILE"
echo "RAG email sent: $RUN_ID"
```
**Expected:** SMTP `250`

### Step 4 — Дождаться классификации RAG и записи файла (до 120 сек)
```bash
for i in $(seq 1 24); do
  sleep 5
  NEW_RAG_LOG=$(tail -n "+$((LOG_BEFORE+1))" logs/JavaMailAgent.log | grep -c "Classified as RAG" 2>/dev/null || echo 0)
  RAG_FILE=$(grep -R -l "$RUN_ID" JavaRagService/rag-inbox/mail/ 2>/dev/null | head -1 || true)
  echo "  Attempt $i/24: rag_log=$NEW_RAG_LOG | file=${RAG_FILE:-not-found}"
  [ "$NEW_RAG_LOG" -ge 1 ] && [ -n "${RAG_FILE:-}" ] && break
done
echo "RAG_FILE=${RAG_FILE:-}"
```
**Expected:** лог содержит `Classified as RAG`, `RAG_FILE` не пустой
**Extract:** `$RAG_FILE`

### Step 5 — Проверить markdown RAG-файла
```bash
echo "$RAG_FILE"
grep -E "^type: RAG$|^source: mail$|^review_by: |^sender: |^subject: " "$RAG_FILE"
grep -E "^## Контекст$|^## Содержание$|^## Возможное применение$" "$RAG_FILE"
grep -n "$RUN_ID" "$RAG_FILE"
```
**Expected:** есть `type: RAG`, `source: mail`, обязательные секции и `RUN_ID`

### Step 6 — Дождаться индексации RagService (до 120 сек)
```bash
for i in $(seq 1 24); do
  sleep 5
  STATUS_JSON=$(curl --max-time 10 -s $RAG_URL/api/rag/status)
  DOCS_AFTER=$(echo "$STATUS_JSON" | jq 'length')
  RAG_STATUS=$(echo "$STATUS_JSON" | jq -r --arg path "$RAG_FILE" '[.[] | select(.filePath == $path)] | first | .status // empty')
  RAG_TYPE=$(echo "$STATUS_JSON" | jq -r --arg path "$RAG_FILE" '[.[] | select(.filePath == $path)] | first | .docType // empty')
  echo "  Attempt $i/24: docs=$DOCS_AFTER | status=${RAG_STATUS:-none} | type=${RAG_TYPE:-none}"
  [ "$DOCS_AFTER" -gt "$DOCS_BEFORE" ] && [ "${RAG_STATUS:-}" = "indexed" ] && [ "${RAG_TYPE:-}" = "RAG" ] && break
done
```
**Expected:** `DOCS_AFTER > DOCS_BEFORE`, `RAG_STATUS=indexed`, `RAG_TYPE=RAG`
**Extract:** `$DOCS_AFTER`, `$RAG_STATUS`, `$RAG_TYPE`

### Step 7 — Семантический поиск в RagService находит RAG document
```bash
SEARCH=$(curl --max-time 10 -s -X POST $RAG_URL/api/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"$RUN_ID release calendar release notes smoke\",\"top_k\":5}")
echo "$SEARCH" | jq '.[0] | {source, score}'
FOUND_COUNT=$(echo "$SEARCH" | jq --arg r "$RUN_ID" '[.[] | select(.text | contains($r))] | length')
echo "FOUND_COUNT=$FOUND_COUNT"
```
**Expected:** `FOUND_COUNT >= 1`

### Step 8 — Memory proxy /api/notices видит новый RAG document
```bash
RAG_DOCS=$(curl --max-time 10 -s $MS_URL/api/notices)
RAG_DOC_ID=$(echo "$RAG_DOCS" | jq -r --arg path "$RAG_FILE" '[.[] | select(.filePath == $path)] | first | .id')
echo "$RAG_DOCS" | jq --arg path "$RAG_FILE" '[.[] | select(.filePath == $path)] | first | {id, docType, status, title, sender}'
echo "RAG_DOC_ID=$RAG_DOC_ID"
```
**Expected:** объект найден, `docType=RAG`, `status=indexed`, `RAG_DOC_ID` числовой
**Extract:** `$RAG_DOC_ID`

### Step 9 — /api/notices/{id} возвращает markdown content
```bash
RAG_DOC_DETAILS=$(curl --max-time 10 -s $MS_URL/api/notices/$RAG_DOC_ID)
echo "$RAG_DOC_DETAILS" | jq '{summary: .summary | {id, docType, status, title}, content_preview: (.content | split("\n")[0:12])}'
DETAIL_FOUND=$(echo "$RAG_DOC_DETAILS" | jq -r --arg r "$RUN_ID" '.content | contains($r)')
echo "DETAIL_FOUND=$DETAIL_FOUND"
```
**Expected:** `DETAIL_FOUND=true`, в `summary.status` значение `indexed`

### Step 10 — UI redirect и Knowledge page работают для RAG
```bash
REDIRECT=$(curl --max-time 10 -s -o /dev/null -w "%{redirect_url}" "$MS_URL/ui/notice")
echo "REDIRECT=$REDIRECT"

HTML=$(curl --max-time 10 -s "$MS_URL/ui/knowledge?type=RAG&id=$RAG_DOC_ID")
echo "$HTML" | grep -E "RAG Knowledge|RAG Documents|$RUN_ID|architect@company.ru" | head -8
```
**Expected:** `REDIRECT=/ui/knowledge?type=RAG`, HTML содержит `RAG Knowledge`, `RAG Documents` и `RUN_ID`

### Step 11 — Изменить RAG document через Memory API
```bash
UPDATED_CONTENT=$(echo "$RAG_DOC_DETAILS" | jq -r --arg run "$RUN_ID" '.content + "\n\n## Проверка E2E\n\n" + $run + " updated through /api/notices.\n"')
UPDATE_RESPONSE=$(curl --max-time 10 -s -X PUT $MS_URL/api/notices/$RAG_DOC_ID \
  -H "Content-Type: application/json" \
  -d "$(jq -nc --arg c "$UPDATED_CONTENT" '{content:$c}')")
echo "$UPDATE_RESPONSE" | jq '{summary: .summary | {id, status, title}}'
UPDATED_STATUS=$(echo "$UPDATE_RESPONSE" | jq -r '.summary.status')
```
**Expected:** `UPDATED_STATUS=outdated`
**Extract:** `$UPDATED_STATUS`

### Step 12 — После edit статус RAG document = outdated
```bash
RAG_DOC_AFTER_UPDATE=$(curl --max-time 10 -s $MS_URL/api/notices/$RAG_DOC_ID)
STATUS_AFTER_UPDATE=$(echo "$RAG_DOC_AFTER_UPDATE" | jq -r '.summary.status')
echo "STATUS_AFTER_UPDATE=$STATUS_AFTER_UPDATE"
```
**Expected:** `STATUS_AFTER_UPDATE=outdated`

### Step 13 — Ручной reindex возвращает indexed
```bash
REINDEX_RESPONSE=$(curl --max-time 10 -s -X POST $MS_URL/api/notices/$RAG_DOC_ID/reindex)
echo "$REINDEX_RESPONSE" | jq '{chunksAdded, status, filePath}'
REINDEX_STATUS=$(echo "$REINDEX_RESPONSE" | jq -r '.status')
echo "REINDEX_STATUS=$REINDEX_STATUS"
```
**Expected:** `REINDEX_STATUS=indexed`

### Step 14 — После reindex документ снова indexed и ищется по новой фразе
```bash
FINAL_RAG_DOC=$(curl --max-time 10 -s $MS_URL/api/notices/$RAG_DOC_ID)
FINAL_STATUS=$(echo "$FINAL_RAG_DOC" | jq -r '.summary.status')
SEARCH_UPDATED=$(curl --max-time 10 -s -X POST $RAG_URL/api/search \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"$RUN_ID updated through api notices\",\"top_k\":5}")
FOUND_UPDATED=$(echo "$SEARCH_UPDATED" | jq --arg r "$RUN_ID updated through /api/notices." '[.[] | select(.text | contains($r))] | length')
echo "FINAL_STATUS=$FINAL_STATUS | FOUND_UPDATED=$FOUND_UPDATED"
```
**Expected:** `FINAL_STATUS=indexed`, `FOUND_UPDATED >= 1`

## Cleanup
```bash
rm -f "$RAG_FILE" 2>/dev/null || true
curl --max-time 10 -s -X DELETE $MAILDEV_URL/email/all > /dev/null
echo "IT-09 cleanup done (indexed RAG record may remain in RAG status/OpenSearch until manual purge)"
```
