# CR-RAG-BUGFIX-003: FileIndexer возвращает stale chunksAdded при status=skipped

**Дата:** 2026-06-13
**Статус:** Draft
**Тип:** BugFix
**Сервис:** JavaRagService
**Источник:** `test-runner/reports/TEST-REPORT-2026-06-13.md`, сценарий `JavaRagService/test_e2e/02_index_single_document.md` Steps 2, 5

---

## Проблема

При повторной индексации уже проиндексированного файла (файл не изменился) API возвращает:
```json
{"chunksAdded": 3, "status": "skipped", "filePath": "..."}
```

`chunksAdded` возвращает значение **предыдущей** индексации (количество чанков из предыдущего прогона), а не `0`.

**Контракт API должен быть:**
```json
{"chunksAdded": 0, "status": "skipped", "filePath": "..."}
```

Семантика `chunksAdded` — количество чанков, **добавленных при данном вызове**. При `status=skipped` ни один чанк не добавлялся → 0.

---

## Локализация

**Файл:** `JavaRagService/src/main/java/ru/andreyz/ragservice/indexer/FileIndexer.java`

**Строки 62-67:**
```java
Optional<IndexedDocument> existing = repository.findByFilePath(filePath);
if (existing.isPresent()
        && existing.get().fileHash().equals(hash)
        && "indexed".equals(existing.get().status())) {
    return new IndexResult(existing.get().chunkCount(), "skipped", filePath);
    //                     ^^^^^^^^^^^^^^^^^^^^^^^^^ ← баг: возвращаем chunkCount из БД
}
```

**Строка 113 (record):**
```java
public record IndexResult(int chunksAdded, String status, String filePath) {}
```

---

## Фикс

Одна строка:

**Файл:** `JavaRagService/src/main/java/ru/andreyz/ragservice/indexer/FileIndexer.java`, строка 66

```java
// Было:
return new IndexResult(existing.get().chunkCount(), "skipped", filePath);

// Стало:
return new IndexResult(0, "skipped", filePath);
```

---

## Влияние

- `POST /api/rag/index` — ответ при повторном вызове того же файла
- `POST /api/rag/index-directory` — суммарный подсчёт в ответе не затронут (используется `indexed`, не `chunksAdded`)
- MCP tool `rag_index` — аналогично, если делегирует в `FileIndexer`

Никакой функциональной деградации нет — данные в OpenSearch и PostgreSQL корректны. Ошибка только в DTO ответа.

---

## Тест

**Файл:** `JavaRagService/src/test/java/ru/andreyz/ragservice/indexer/FileIndexerTest.java`

Добавить кейс:
```java
@Test
void indexFile_secondCall_samHash_returnsZeroChunksAdded() {
    // arrange: файл уже проиндексирован (status=indexed, chunkCount=3)
    // act: indexFile() для того же файла с тем же hash
    // assert: result.chunksAdded() == 0
    //         result.status() == "skipped"
}
```

---

## Acceptance Criteria

```bash
# 1. Проиндексировать файл первый раз:
FIRST=$(curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/test-doc.md"}')
echo "First: $(echo $FIRST | jq '{chunksAdded, status}')"
# Expected: chunksAdded > 0, status="indexed"

# 2. Проиндексировать тот же файл ещё раз (без изменений):
SECOND=$(curl -s --max-time 30 -X POST http://localhost:8081/api/rag/index \
  -H "Content-Type: application/json" \
  -d '{"file_path":"rag-inbox/test-doc.md"}')
echo "Second: $(echo $SECOND | jq '{chunksAdded, status}')"
# Expected: chunksAdded=0, status="skipped"  ← было: chunksAdded=N
```

После фикса сценарий `JavaRagService/test_e2e/02_index_single_document.md` Steps 2 и 5: PASS.

---

## Приоритет

**LOW** — корректность данных в OpenSearch/Postgres не нарушена. Ошибочное значение в DTO может вводить в заблуждение клиентов, полагающихся на `chunksAdded` для мониторинга идемпотентных запусков (CI, скрипты).
