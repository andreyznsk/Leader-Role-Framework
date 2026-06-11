# CR-RAG-002: Валидация структуры документов перед индексацией

**Дата:** 2026-06-11  
**Статус:** Approved  
**Сервис:** RAG  
**Зависимости:** CR-RAG-001 (схема rag в PostgreSQL)

---

## Проблема / Мотивация

Без валидации в `rag-inbox/` может попасть что угодно:
- сырые заметки со встреч
- необработанный экспорт из Confluence
- файлы без frontmatter
- документы не того типа

RAG с неструктурированными chunks даёт противоречивые и неточные ответы.
Нужна валидация на входе — до индексации.

---

## Решение

1. Ввести enum типов документов (`DocType`)
2. Описать обязательные секции для каждого типа (`DocSchema`)
3. Добавить `DocumentValidator` — проверяет frontmatter и структуру заголовков
4. При ошибке — пометить файл в PostgreSQL статусом `invalid`, записать описание ошибки
5. Логировать предупреждение, не останавливать обработку остальных файлов

---

## Изменения

### 1. Enum DocType

```java
// ru.andreyz.ragservice.validation.DocType
public enum DocType {
    SERVICE_CARD,   // карточка сервиса
    PROCESS,        // процесс команды
    GLOSSARY,       // глоссарий сокращений
    ADR             // architectural decision record
}
```

---

### 2. Enum DocField — обязательные поля frontmatter

```java
// ru.andreyz.ragservice.validation.DocField
public enum DocField {
    TYPE("type"),
    SERVICE("service"),
    UPDATED("updated"),
    REVIEW_BY("review_by"),
    SOURCE("source");

    private final String key;

    DocField(String key) { this.key = key; }
    public String key() { return key; }
}
```

---

### 3. Обязательная структура по типу документа

```java
// ru.andreyz.ragservice.validation.DocSchema
public enum DocSchema {

    SERVICE_CARD(DocType.SERVICE_CARD,
        List.of(DocField.TYPE, DocField.SERVICE, DocField.UPDATED, DocField.REVIEW_BY),
        List.of("## Назначение", "## Стек", "## Интеграции", "## Деплой")
    ),

    PROCESS(DocType.PROCESS,
        List.of(DocField.TYPE, DocField.UPDATED, DocField.REVIEW_BY),
        List.of("## Когда использовать", "## Шаги", "## Кто участвует", "## Escalation")
    ),

    GLOSSARY(DocType.GLOSSARY,
        List.of(DocField.TYPE, DocField.UPDATED),
        List.of("# Глоссарий")
    ),

    ADR(DocType.ADR,
        List.of(DocField.TYPE, DocField.UPDATED),
        List.of("## Статус", "## Контекст", "## Решение", "## Последствия")
    );

    private final DocType docType;
    private final List<DocField> requiredFrontmatterFields;
    private final List<String> requiredSections;

    // constructor + getters
}
```

---

### 4. DocumentValidator

```java
// ru.andreyz.ragservice.validation.DocumentValidator
@Component
public class DocumentValidator {

    /**
     * Валидирует .md файл по структуре.
     * @return ValidationResult — ok или список ошибок
     */
    public ValidationResult validate(Path filePath) {
        String content;
        try {
            content = Files.readString(filePath);
        } catch (IOException e) {
            return ValidationResult.error("Не удалось прочитать файл: " + e.getMessage());
        }

        // 1. Проверить наличие frontmatter (--- блок)
        if (!content.startsWith("---")) {
            return ValidationResult.error("Отсутствует frontmatter (файл должен начинаться с ---)");
        }

        // 2. Извлечь frontmatter
        Map<String, String> frontmatter = parseFrontmatter(content);

        // 3. Проверить поле type
        String typeRaw = frontmatter.get(DocField.TYPE.key());
        if (typeRaw == null || typeRaw.isBlank()) {
            return ValidationResult.error("Поле 'type' отсутствует в frontmatter. " +
                "Допустимые значения: " + Arrays.toString(DocType.values()));
        }

        // 4. Определить DocSchema по type
        DocType docType;
        try {
            docType = DocType.valueOf(typeRaw.toUpperCase().replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return ValidationResult.error("Неизвестный тип документа: '" + typeRaw + "'. " +
                "Допустимые: " + Arrays.toString(DocType.values()));
        }

        DocSchema schema = DocSchema.forType(docType);
        List<String> errors = new ArrayList<>();

        // 5. Проверить обязательные поля frontmatter
        for (DocField field : schema.requiredFrontmatterFields()) {
            if (!frontmatter.containsKey(field.key()) || frontmatter.get(field.key()).isBlank()) {
                errors.add("Отсутствует обязательное поле frontmatter: '" + field.key() + "'");
            }
        }

        // 6. Проверить обязательные секции в теле документа
        for (String section : schema.requiredSections()) {
            if (!content.contains(section)) {
                errors.add("Отсутствует обязательная секция: '" + section + "'");
            }
        }

        if (errors.isEmpty()) {
            return ValidationResult.ok(docType);
        }
        return ValidationResult.errors(errors);
    }
}
```

---

### 5. ValidationResult

```java
// ru.andreyz.ragservice.validation.ValidationResult
public record ValidationResult(
    boolean valid,
    DocType docType,        // заполнен если valid = true
    List<String> errors     // заполнен если valid = false
) {
    public static ValidationResult ok(DocType docType) {
        return new ValidationResult(true, docType, List.of());
    }

    public static ValidationResult error(String message) {
        return new ValidationResult(false, null, List.of(message));
    }

    public static ValidationResult errors(List<String> errors) {
        return new ValidationResult(false, null, errors);
    }

    public String errorsAsString() {
        return String.join("; ", errors);
    }
}
```

---

### 6. Изменения в FileIndexer

```java
@Component
public class FileIndexer {

    private final DocumentValidator validator;
    private final IndexedDocumentRepository repository;
    // ... остальные зависимости

    public void indexFile(Path filePath) {
        String filePathStr = filePath.toString();

        // 1. Валидация структуры
        ValidationResult validation = validator.validate(filePath);

        if (!validation.valid()) {
            // Записать в PostgreSQL как invalid
            repository.upsert(IndexedDocument.invalid(
                filePathStr,
                computeHash(filePath),
                validation.errorsAsString()
            ));
            log.warn("⚠️  Файл не прошёл валидацию, индексация пропущена: {} — {}",
                filePathStr, validation.errorsAsString());
            return;
        }

        // 2. Проверить idempotency (hash не изменился)
        String currentHash = computeHash(filePath);
        Optional<IndexedDocument> existing = repository.findByFilePath(filePathStr);
        if (existing.isPresent()
                && existing.get().fileHash().equals(currentHash)
                && "indexed".equals(existing.get().status())) {
            log.debug("Файл не изменился, пропускаем: {}", filePathStr);
            return;
        }

        // 3. Удалить старые chunks если переиндексация
        if (existing.isPresent()) {
            openSearchClient.deleteBySource(filePathStr);
        }

        // 4. Индексировать
        try {
            List<String> chunks = chunkSplitter.split(filePath);
            for (int i = 0; i < chunks.size(); i++) {
                float[] vector = ollamaClient.embed(chunks.get(i));
                openSearchClient.index(filePathStr, chunks.get(i), vector, i);
            }

            repository.upsert(IndexedDocument.indexed(filePathStr, currentHash, chunks.size()));
            log.info("✅ Проиндексировано: {} ({} chunks)", filePathStr, chunks.size());

        } catch (Exception e) {
            repository.upsert(IndexedDocument.failed(filePathStr, currentHash, e.getMessage()));
            log.error("❌ Ошибка индексации: {} — {}", filePathStr, e.getMessage());
        }
    }
}
```

---

### 7. Изменения в схеме PostgreSQL

Добавить колонку `error_message` в таблицу `indexed_documents`:

Новая миграция `V2__add_error_message.sql`:

```sql
ALTER TABLE indexed_documents
    ADD COLUMN IF NOT EXISTS error_message TEXT;
```

Итоговая структура таблицы:

```sql
CREATE TABLE indexed_documents (
    id            SERIAL PRIMARY KEY,
    file_path     TEXT        NOT NULL UNIQUE,
    file_hash     TEXT        NOT NULL,
    indexed_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    chunk_count   INT,
    status        TEXT        NOT NULL DEFAULT 'indexed',
    error_message TEXT        -- заполняется при status = invalid | failed
    -- статусы: indexed | invalid | failed | outdated
);
```

---

### 8. IndexedDocument record — обновлённый

```java
public record IndexedDocument(
    Long id,
    String filePath,
    String fileHash,
    Instant indexedAt,
    Integer chunkCount,
    String status,
    String errorMessage
) {
    public static IndexedDocument indexed(String path, String hash, int chunks) {
        return new IndexedDocument(null, path, hash, Instant.now(), chunks, "indexed", null);
    }

    public static IndexedDocument invalid(String path, String hash, String error) {
        return new IndexedDocument(null, path, hash, Instant.now(), 0, "invalid", error);
    }

    public static IndexedDocument failed(String path, String hash, String error) {
        return new IndexedDocument(null, path, hash, Instant.now(), 0, "failed", error);
    }
}
```

---

### 9. Обновить rag_status MCP tool

Вернуть `error_message` в ответе чтобы агент видел проблемные файлы:

```json
[
  {
    "file_path": "rag-inbox/services/КСК-service-card.md",
    "status": "indexed",
    "chunk_count": 5,
    "indexed_at": "2026-06-11T10:00:00Z",
    "error_message": null
  },
  {
    "file_path": "rag-inbox/raw-notes.md",
    "status": "invalid",
    "chunk_count": 0,
    "indexed_at": "2026-06-11T10:01:00Z",
    "error_message": "Отсутствует frontmatter; Отсутствует обязательная секция: '## Назначение'"
  }
]
```

---

## Примеры ошибок валидации

| Ситуация | Сообщение в error_message |
|----------|--------------------------|
| Нет frontmatter | `Отсутствует frontmatter (файл должен начинаться с ---)` |
| Нет поля type | `Поле 'type' отсутствует в frontmatter. Допустимые значения: [SERVICE_CARD, PROCESS, GLOSSARY, ADR]` |
| Неизвестный type | `Неизвестный тип документа: 'wiki'. Допустимые: [SERVICE_CARD, PROCESS, GLOSSARY, ADR]` |
| Нет поля service у SERVICE_CARD | `Отсутствует обязательное поле frontmatter: 'service'` |
| Нет секции ## Назначение | `Отсутствует обязательная секция: '## Назначение'` |
| Нет review_by | `Отсутствует обязательное поле frontmatter: 'review_by'` |

---

## Новая структура пакетов

```
ru.andreyz.ragservice/
└── validation/
    ├── DocType.java
    ├── DocField.java
    ├── DocSchema.java
    ├── ValidationResult.java
    └── DocumentValidator.java
```

---

## Как тестировать

```bash
# Тест 1 — файл без frontmatter
echo "# Просто заголовок без frontmatter" > rag-inbox/test/bad-no-frontmatter.md
sleep 70
psql -U rag_user -d leader_framework \
  -c "SELECT status, error_message FROM rag.indexed_documents
      WHERE file_path LIKE '%bad-no-frontmatter%';"
# Ожидаем: status=invalid, error_message содержит "Отсутствует frontmatter"

# Тест 2 — файл с неизвестным type
cat > rag-inbox/test/bad-unknown-type.md << 'EOF'
---
type: wiki
updated: 2026-06-11
---
# Какой-то документ
EOF
sleep 70
psql -U rag_user -d leader_framework \
  -c "SELECT status, error_message FROM rag.indexed_documents
      WHERE file_path LIKE '%bad-unknown-type%';"
# Ожидаем: status=invalid, error_message содержит "Неизвестный тип документа"

# Тест 3 — валидный SERVICE_CARD проходит
cat > rag-inbox/test/good-service-card.md << 'EOF'
---
type: service-card
service: TST
updated: 2026-06-11
review_by: 2026-09-11
source: manual
---
# TestService (TST)
## Назначение
Тест.
## Стек
- Java 21
## Интеграции
- нет
## Деплой
- Kubernetes
EOF
sleep 70
psql -U rag_user -d leader_framework \
  -c "SELECT status, chunk_count FROM rag.indexed_documents
      WHERE file_path LIKE '%good-service-card%';"
# Ожидаем: status=indexed, chunk_count > 0

# Очистка
rm rag-inbox/test/bad-*.md rag-inbox/test/good-*.md
```

---

## Статусы файла — полная таблица

| Статус | Когда | chunk_count | error_message |
|--------|-------|-------------|---------------|
| `indexed` | успешно проиндексирован | > 0 | null |
| `invalid` | не прошёл валидацию структуры | 0 | описание ошибок |
| `failed` | ошибка при индексации (Ollama/OpenSearch недоступен) | 0 | текст исключения |
| `outdated` | файл изменился, идёт переиндексация | старый | null |
