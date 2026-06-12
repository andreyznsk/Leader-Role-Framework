# TEST-REPORT-2026-06-12-rag

**Запуск:** 2026-06-12 16:03 — 16:20 +07:00
**Профиль:** local
**Инициатор:** ручной запуск
**Сервис:** JavaRagService (port 8081)

---

## Summary

| Сценарий | Приоритет | PASS | FAIL | SKIP | Итог |
|----------|-----------|------|------|------|------|
| 01_health_check | CRITICAL | 5 | 1 | 0 | ⚠️ PARTIAL |
| 02_index_and_search | HIGH | 1 | 2 | 0 | ❌ FAIL |
| 02_index_single_document | HIGH | 2 | 4 | 0 | ❌ FAIL |
| 03_semantic_search | HIGH | 1 | 6 | 0 | ❌ FAIL |
| 04_scheduler_auto_index | HIGH | 1 | 4 | 3 | ⚠️ PARTIAL |
| 05_index_directory | MEDIUM | 3 | 3 | 0 | ⚠️ PARTIAL |
| 06_reindex_on_change | MEDIUM | 6 | 1 | 0 | ⚠️ PARTIAL |
| **Итого** | | **19** | **21** | **3** | |

> **Корневая причина большинства FAIL:** в сценариях 02-05 тестовые документы не содержат обязательного frontmatter, добавленного в сервис после написания тестов. Это системная проблема несоответствия тест-сценариев текущей версии сервиса.

---

## Предварительные условия

### Инфраструктура (Docker) — ✅ OK
| Сервис | Статус |
|--------|--------|
| PostgreSQL :5432 | ✅ |
| OpenSearch :9200 (172.80.2.1) | ✅ |
| Ollama :11434 | ✅ |
| Maildev | ✅ (не нужен для RAG) |

### JavaRagService — ✅ запущен (PID 74696)

---

## 01_health_check — PARTIAL (5 PASS / 1 FAIL)

### ✅ Step 1 — JavaRagService health (HTTP 200)
```
HTTP: 200 → PASS
```

### ❌ Step 2 — OpenSearch status
**Expected:** поле `status` = `green` или `yellow` из корневого ответа  
**Actual:** OpenSearch 3.5.0 убрал поле `status` из корневого `GET /` ответа  
**Фактический статус кластера:** `yellow` (получен через `/_cluster/health`)  
**Вывод:** тест написан под OpenSearch 2.x API — **требует обновления сценария**

```json
// GET http://172.80.2.1:9200 — нет поля status
// GET http://172.80.2.1:9200/_cluster/health → status: "yellow" ✅
```

### ✅ Step 3 — Ollama + модели
Модели: `mxbai-embed-large:latest`, `zylonai/multilingual-e5-large:latest`, `qwen3:8b`  
Паттерн `multilingual-e5-large` найден → PASS

### ✅ Step 4 — PostgreSQL: таблица rag.indexed_documents доступна
```
count = 6 → PASS
```
> Примечание: `psql` не установлен локально, выполнено через `docker exec leader-postgres`

### ✅ Step 5 — OpenSearch индекс rag-knowledge
```
HTTP: 200 (индекс существует) → PASS
```

### ✅ Step 6 — rag_status endpoint
**Примечание:** endpoint изменён — тест ссылается на `/mcp/rag_status` (404),  
фактический endpoint: `GET /api/rag/status`  
```json
HTTP: 200, count: 6 → PASS (с учётом исправленного URL)
```

---

## 02_index_and_search — FAIL (1 PASS / 2 FAIL)

**Корневая причина:** тестовый документ не содержит frontmatter → статус `invalid`, chunksAdded=0

### ✅ Step 1 — Файл создан
### ❌ Step 2 — rag_index через `/api/rag/index`
```json
{"chunksAdded":0,"status":"invalid","filePath":"rag-inbox/e2e-test-doc.md"}
```
**Expected:** chunks_added > 0  
**Actual:** status=invalid (Отсутствует frontmatter)

### ❌ Step 3 — Семантический поиск
**Expected:** результаты содержат source e2e-test-doc.md  
**Actual:** документ не проиндексирован, в поиске не найден

**Примечание:** endpoint тоже расходится — тест использует `POST /mcp`, фактический: `POST /api/rag/index`

---

## 02_index_single_document — FAIL (2 PASS / 4 FAIL)

**Корневая причина:** те же — отсутствие frontmatter

### ✅ Step 1 — Файл создан (13 строк > 5)
### ❌ Step 2 — rag_index
```json
{"chunksAdded":0,"status":"invalid",...}
```
### ✅ Step 3 — Запись появилась в indexed_documents
```
file_path=rag-inbox/e2e-test-doc.md | chunk_count=0 | status=invalid → запись есть, но status=invalid
```
> Сервис сохраняет запись даже для invalid документов (для отслеживания) — функционально корректно

### ❌ Step 4 — Чанки в OpenSearch
**Actual:** total=0 (нет чанков — документ не прошёл валидацию)

### ❌ Step 5 — Идемпотентность
**Expected:** chunks_added=0 или status=skipped  
**Actual:** status=invalid при повторном вызове (файл не изменился, hash совпал — но тест ожидает "skipped", получает "invalid")

### ✅ Step 6 — rag_status показывает документ
**Примечание:** endpoint `/mcp/rag_status` → фактически `GET /api/rag/status`  
Запись найдена (status=invalid) — информационно корректно

---

## 03_semantic_search — FAIL (1 PASS / 6 FAIL)

### ✅ Step 1 — Файл release создан
### ✅ Step 2 — Файл onboarding создан
### ❌ Step 3 — Индексация обоих документов
```json
{"chunksAdded":0,"status":"invalid",...}  // оба документа
```
### ❌ Steps 4-6 — Семантический поиск
Документы не проиндексированы → поиск возвращает другие (существующие) документы

### ✅ Step 7 — top_k параметр
```
top_k=2 → count=2 → PASS
```
Поиск возвращает корректное число результатов (≤ top_k)

---

## 04_scheduler_auto_index — PARTIAL (1 PASS / 4 FAIL / 3 не выполнены)

### ⚠️ Step 1 — Предусловие
В БД уже была 1 запись от предыдущего теста (count=1, not 0)

### ✅ Step 2 — Файл создан в rag-inbox/ без вызова rag_index
### ❌ Step 3 — Автоиндексация scheduler за 90 сек
Scheduler **РАБОТАЕТ** — он обнаружил файл и создал запись в БД (`status=invalid`).  
Но тест ожидает `status=indexed`, а документ без frontmatter → статус `invalid`.  
```
indexed_documents: file_path=e2e-scheduler-test.md | status=invalid | file_hash=c72fb9...
```
**Вывод:** scheduler функционально корректен (подхватывает файлы), но документ не прошёл валидацию.

### ❌ Step 4 — indexed_documents: status=indexed
Actual: status=invalid

### ❌ Step 5 — Файл доступен через поиск
Не проиндексирован → не найден

### Steps 6-8 — не выполнены (Step 3 FAIL)

---

## 05_index_directory — PARTIAL (3 PASS / 3 FAIL)

### ✅ Step 1 — Созданы 3 .md + 1 .txt файла

### ❌ Step 2 — rag_index_directory
```json
{"indexed":3,"skipped":0,"failed":0,"message":"done"}
```
**Примечание:** endpoint в тесте — `POST /mcp` с `method:rag_index_directory`, фактически: `POST /api/rag/index-directory` с body `{"dir_path":...,"pattern":...}`  
**BUG:** ответ показывает `indexed:3`, но реально все 3 документа получили `status=invalid` (нет frontmatter). Метрика `indexed` вводит в заблуждение.

### ✅ Step 3 — 3 записи появились в indexed_documents
```
adr-001.md | 0 chunks | invalid
adr-002.md | 0 chunks | invalid
runbook.md | 0 chunks | invalid
```

### ✅ Step 4 — .txt файл НЕ проиндексирован
```
COUNT(*) WHERE file_path LIKE '%ignore.txt%' = 0 → PASS
```

### ✅ Step 5 — Повторный вызов — файлы пропускаются (идемпотентность)
```json
{"indexed":0,"skipped":3,"failed":0,"message":"done"} → PASS
```
Хэш не изменился → correctly skipped

### ❌ Step 6 — Поиск находит батч-индексированные документы
Фактически в индексе только документы с фронтматтером → батч-документы не найдены

---

## 06_reindex_on_change — PARTIAL (6 PASS / 1 FAIL)

> Сценарий выполнялся с **корректным frontmatter** (ADR тип) — проверка переиндексации

### ✅ Step 1-2 — Создание и индексация V1
```json
{"chunksAdded":2,"status":"indexed","filePath":"rag-inbox/e2e-reindex-test.md"}
```

### ✅ Step 2 — Поиск находит V1 контент (ZEBRA-UNIQUE-V1-PHRASE)
```json
[{score: 0.859, source: "e2e-reindex-test.md", chunkIndex: 1}]
```
ZEBRA-фраза найдена с высоким score → PASS

### ✅ Step 4 — V1 hash записан
```
hash: 92fb1a05...
```

### ✅ Step 5 — Переиндексация V2
```json
{"chunksAdded":3,"status":"indexed",...}
```

### ✅ Step 6 — Hash изменился
```
V1: 92fb1a05...
V2: 20c379f6...
Разные → PASS
```

### ✅ Step 7 — Поиск находит V2 контент (ELEPHANT-UNIQUE-V2-PHRASE)
```json
[{score: 0.826, text: "...ELEPHANT-UNIQUE-V2-PHRASE..."}]
```

### ❌ Step 8 — Старый V1 контент больше не находится
**Expected:** 0 результатов для ZEBRA-UNIQUE-V1-PHRASE из e2e-reindex-test  
**Actual:** 2 результата — НО это V2 чанки, которые упоминают фразу в тексте `## Последствия`

**Анализ:** В OpenSearch осталось ровно 3 чанка (все V2):
- `chunk_0`: frontmatter + заголовок V2
- `chunk_1`: ## Контекст с ELEPHANT-фразой  
- `chunk_2`: ## Решение + ## Последствия, где упоминается ZEBRA-фраза

**Вывод:** Старые V1 чанки **успешно удалены** из OpenSearch. Тест PASS с оговоркой — в V2 документе намеренно написан текст о том, что ZEBRA-фраза "не должна находиться", что вызывает ложное срабатывание поиска. Функционально переиндексация работает корректно.

---

## Обнаруженные проблемы

### BUG-RAG-001 — Несоответствие API в тест-сценариях (КРИТИЧНО для тестов)

**Затронутые сценарии:** 01 Step 6, 02, 03, 04, 05 Step 2

Тест-сценарии написаны под `/mcp` endpoint, который не существует в текущей версии:

| Тест использует | Фактический endpoint |
|-----------------|----------------------|
| `POST /mcp` (method: rag_index) | `POST /api/rag/index` |
| `POST /mcp` (method: rag_index_directory) | `POST /api/rag/index-directory` |
| `GET /mcp/rag_status` | `GET /api/rag/status` |

**Fix:** обновить все test_e2e/*.md сценарии под REST API.

---

### BUG-RAG-002 — Тестовые документы не содержат обязательного frontmatter (КРИТИЧНО)

**Затронутые сценарии:** 02, 03, 04, 05

Все тест-документы (e2e-test-doc.md, e2e-release-process.md, e2e-onboarding.md, e2e-scheduler-test.md, batch-test/*.md) не имеют frontmatter. После добавления `DocumentValidator` сервис отклоняет их.

**Fix:** добавить в тест-документы валидный frontmatter:
```markdown
---
type: ADR
title: <название>
status: active
updated: <YYYY-MM-DD>
---
## Статус
...
## Контекст
...
## Решение
...
## Последствия
...
```

---

### BUG-RAG-003 — Метрика `indexed` в ответе `/api/rag/index-directory` вводит в заблуждение

**Сценарий:** 05 Step 2

Ответ `{"indexed":3,"skipped":0,"failed":0}` при том, что все 3 файла получили `status=invalid` в БД.  
Счётчик `indexed` считает "обработанные" файлы, а не успешно проиндексированные.

**Fix:** переименовать поле или разделить на `indexed_ok` / `invalid` / `skipped` / `failed`.

---

### BUG-RAG-004 — OpenSearch 3.x: поле `status` убрано из корневого ответа

**Сценарий:** 01 Step 2

Step 2 тестирует `curl $OPENSEARCH_URL | jq '.status'`, что вернёт `null` в OpenSearch 3.5.0.  
**Fix:** изменить шаг на `curl $OPENSEARCH_URL/_cluster/health | jq '.status'`.

---

## Что работает (позитивные наблюдения)

1. **Сервис стартует и отвечает** — health ✅, все actuator endpoints ✅
2. **Валидация frontmatter работает** — некорректные документы помечаются `invalid`, не попадают в поиск
3. **Индексация с frontmatter работает** (подтверждено в сценарии 06 — документы ADR-типа проиндексировались)
4. **Семантический поиск работает** — возвращает релевантные результаты по валидным документам
5. **Переиндексация корректна** — старые чанки удаляются при обновлении, hash обновляется
6. **Идемпотентность** — повторная индексация без изменений пропускает файл (`skipped`)
7. **Scheduler работает** — подхватывает новые файлы из rag-inbox автоматически
8. **Фильтр по типу файла** — .txt файлы не индексируются при batch-индексации
9. **top_k параметр** — корректно ограничивает число результатов

---

## Рекомендации к CR

### CR-RAG-001 — Обновить тест-сценарии под REST API (приоритет: HIGH)
- Заменить все вызовы `POST /mcp` на соответствующие `/api/rag/*` endpoints
- Заменить `GET /mcp/rag_status` на `GET /api/rag/status`
- Добавить frontmatter во все тестовые документы
- Файлы: `JavaRagService/test_e2e/01_health_check.md`, `02_index_and_search.md`, `02_index_single_document.md`, `03_semantic_search.md`, `04_scheduler_auto_index.md`, `05_index_directory.md`

### CR-RAG-002 — Исправить метрику в DirectoryIndexResult (приоритет: MEDIUM)
- Файл: `RagMcpTools.java` → `DirectoryIndexResult`
- Разделить `indexed` на `indexed` (успешно) и `invalid` (failed validation)

### CR-RAG-003 — Обновить Step 2 в 01_health_check.md (приоритет: LOW)
- `jq '.status'` → `/_cluster/health | jq '.status'`
- Или добавить проверку через `/api/rag/status` endpoint самого сервиса
