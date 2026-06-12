# TEST-REPORT-2026-06-12-rag-after-cr

**Запуск:** 2026-06-12 16:20 — 16:45 +07:00
**Профиль:** local
**Инициатор:** после выполнения CR-RAG-E2E-001
**Сервис:** JavaRagService (port 8081)

---

## Summary

| Сценарий | Приоритет | Шагов | PASS | FAIL | SKIP | Итог |
|----------|-----------|-------|------|------|------|------|
| 01_health_check | CRITICAL | 6 | 6 | 0 | 0 | ✅ PASS |
| 02_index_and_search | HIGH | 3 | 3 | 0 | 0 | ✅ PASS |
| 02_index_single_document | HIGH | 6 | 6 | 0 | 0 | ✅ PASS |
| 03_semantic_search | HIGH | 7 | 7 | 0 | 0 | ✅ PASS |
| 04_scheduler_auto_index | HIGH | 8 | 8 | 0 | 0 | ✅ PASS |
| 05_index_directory | MEDIUM | 6 | 6 | 0 | 0 | ✅ PASS |
| 06_reindex_on_change | MEDIUM | 8 | 8 | 0 | 0 | ✅ PASS |
| **Итого** | | **44** | **44** | **0** | **0** | |

---

## 01_health_check — ✅ PASS (6/6)

| Step | Результат |
|------|-----------|
| Step 1 — JavaRagService health | ✅ HTTP 200 |
| Step 2 — OpenSearch cluster health | ✅ status=yellow (через `/_cluster/health`) |
| Step 3 — Ollama + multilingual-e5-large | ✅ модель найдена |
| Step 4 — PostgreSQL indexed_documents | ✅ count=13 |
| Step 5 — OpenSearch index rag-knowledge | ✅ HTTP 200 |
| Step 6 — GET /api/rag/status | ✅ JSON массив, count=13 |

---

## 02_index_and_search — ✅ PASS (3/3)

| Step | Результат |
|------|-----------|
| Step 1 — Файл создан (с frontmatter) | ✅ |
| Step 2 — POST /api/rag/index → indexed | ✅ chunksAdded=3, status=indexed |
| Step 3 — /api/search находит документ | ✅ source=rag-inbox/e2e-test-doc.md в результатах |

---

## 02_index_single_document — ✅ PASS (6/6)

| Step | Результат |
|------|-----------|
| Step 1 — Файл создан | ✅ |
| Step 2 — /api/rag/index | ✅ chunksAdded=3, status=indexed |
| Step 3 — Запись в indexed_documents | ✅ status=indexed, chunk_count=3 |
| Step 4 — Чанки в OpenSearch | ✅ total=3 |
| Step 5 — Идемпотентность (повтор) | ✅ status=skipped, chunksAdded=0 |
| Step 6 — /api/rag/status показывает документ | ✅ |

---

## 03_semantic_search — ✅ PASS (7/7)

| Step | Результат |
|------|-----------|
| Step 1 — release doc создан | ✅ |
| Step 2 — onboarding doc создан | ✅ |
| Step 3 — Оба проиндексированы | ✅ c1=3, c2=3 |
| Step 4 — Запрос релиз → release doc первый | ✅ first=e2e-release-process.md |
| Step 5 — Запрос онбординг → onboarding doc первый | ✅ first=e2e-onboarding.md |
| Step 6 — Rollback запрос → release doc | ✅ hits=1 |
| Step 7 — top_k=2 → count≤2 | ✅ count=2 |

---

## 04_scheduler_auto_index — ✅ PASS (8/8)

| Step | Результат |
|------|-----------|
| Step 1 — Нет записи в indexed_documents | ✅ count=0 |
| Step 2 — Файл создан в rag-inbox/ без вызова index | ✅ 802 bytes, 16:33:07 |
| Step 3 — Автоиндексация scheduler | ✅ **за 5 секунд** (attempt 1/18) |
| Step 4 — Запись корректна в indexed_documents | ✅ status=indexed, chunk_count=3, file_hash непустой |
| Step 5 — Поиск находит документ | ✅ found=2 чанка |
| Step 6 — Файл изменён (добавлена секция `## Обновление`) | ✅ 16:33:56 |
| Step 7 — Переиндексация изменённого файла | ✅ **за 5 секунд** (attempt 1/18) |
| Step 8 — Лог содержит записи scheduler | ✅ `IndexScheduler: Indexing new/changed file` |

**Детали из лога:**
```
16:33:06 IndexScheduler : Indexing new/changed file: rag-inbox/e2e-scheduler-test.md
16:33:09 FileIndexer    : ✅ Indexed 3 chunks from rag-inbox/e2e-scheduler-test.md
16:34:10 IndexScheduler : Indexing new/changed file: rag-inbox/e2e-scheduler-test.md
16:34:14 FileIndexer    : ✅ Indexed 4 chunks from rag-inbox/e2e-scheduler-test.md
```

**Hash до изменения:** `1eef7426...`
**Hash после изменения:** `db696834...`

---

## 05_index_directory — ✅ PASS (6/6)

| Step | Результат |
|------|-----------|
| Step 1 — 3 .md + 1 .txt созданы | ✅ |
| Step 2 — /api/rag/index-directory | ✅ indexed=3, skipped=0, invalid=0, failed=0 |
| Step 3 — 3 записи в indexed_documents | ✅ status=indexed |
| Step 4 — .txt НЕ проиндексирован | ✅ count=0 |
| Step 5 — Повторный вызов — skip | ✅ indexed=0, skipped=3 |
| Step 6 — Поиск находит батч-документы | ✅ hits=3 |

---

## 06_reindex_on_change — ✅ PASS (8/8)

| Step | Результат |
|------|-----------|
| Step 1-2 — V1 создан и проиндексирован | ✅ chunksAdded=2, status=indexed |
| Step 2 — Поиск находит ZEBRA-фразу | ✅ found=1 |
| Step 3 — V1 hash записан | ✅ 100bf62a... |
| Step 4 — Файл обновлён до V2 | ✅ |
| Step 5 — Переиндексация V2 | ✅ chunksAdded=3, status=indexed |
| Step 6 — Hash изменился | ✅ b576988d... ≠ 100bf62a... |
| Step 7 — Поиск находит ELEPHANT-фразу | ✅ found=1 |
| Step 8 — ZEBRA-фраза больше не в индексе | ✅ V1 chunks=0 |

---

## Что изменилось в CR-RAG-E2E-001

### Код
- `RagMcpTools.DirectoryIndexResult` — добавлено поле `invalid`
- `ragIndexDirectory()` — документы со статусом `invalid` теперь считаются в `invalid`, не в `indexed`

### Тест-сценарии (все 6 файлов)
- Все endpoints исправлены: `/mcp` → `/api/rag/*`
- `psql` заменён на `docker exec leader-postgres psql`
- OpenSearch healthcheck: `GET /` → `GET /_cluster/health`
- Все тестовые документы содержат валидный ADR frontmatter
- Step 8 сценария 06: фикс фильтра jq (проверяет именно текст чанка, а не только источник)

### Конфигурация
- `env.sh`: `OPENSEARCH_URL` исправлен с `localhost:9200` на `172.80.2.1:9200`
