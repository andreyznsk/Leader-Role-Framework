# CR-001: План сквозного тестирования JavaRagService

**Сервис:** JavaRagService  
**Дата:** 2026-06-09  
**Автор:** Андрей Зайцев  
**Статус:** Draft

---

## Фаза 0 — Предусловия (инфраструктура)

```bash
# 1. PostgreSQL
psql -h localhost -U postgres -d leader_framework -c "\dt"
# ожидание: таблица indexed_documents после старта сервиса

# 2. OpenSearch
curl http://localhost:9200/_cluster/health
# ожидание: status green или yellow

# 3. Ollama + модель
curl http://localhost:11434/api/tags | jq '.models[].name'
# ожидание: "multilingual-e5-large" в списке

# 4. rag-inbox папка
ls -la ../rag-inbox
# создать если не существует
mkdir -p ../rag-inbox
```

---

## Фаза 1 — Старт сервиса

```bash
SPRING_PROFILES_ACTIVE=local java -jar target/rag-service.jar
```

**Что проверить в логах:**
- `Flyway` выполнил миграцию `V1__create_indexed_documents`
- `Created OpenSearch index 'rag-knowledge'` (или "already exists" при повторном запуске)
- `Started RagServiceApplication` на порту 8081
- `@Scheduled` запустился без ошибок

```bash
# Health check
curl http://localhost:8081/actuator/health
# ожидание: {"status":"UP"}

# OpenSearch index создан с нужной схемой
curl http://localhost:9200/rag-knowledge/_mapping | jq '.["rag-knowledge"].mappings.properties'
# ожидание: поле "vector" с type=knn_vector, dimension=1024
```

---

## Фаза 2 — Индексация одного файла (MCP: `rag_index`)

**Подготовка тестового документа:**
```bash
cat > ../rag-inbox/test-adr-001.md << 'EOF'
# ADR-001: Использование PostgreSQL для хранения состояния

## Решение
Команда выбрала PostgreSQL как основное хранилище оперативных данных.
Причина: зрелость, ACID-гарантии, знакомство команды.

## Последствия
Все сервисы подключаются к единому PostgreSQL инстансу.
Миграции управляются через Flyway.
EOF
```

**Тест через MCP (добавить в `.mcp.json` и вызвать через Claude):**
```
rag_index("../rag-inbox/test-adr-001.md")
```

**Проверки после индексации:**
```bash
# 1. Запись в PostgreSQL
psql -h localhost -U postgres -d leader_framework \
  -c "SELECT file_path, chunk_count, status FROM indexed_documents;"
# ожидание: строка с status=indexed, chunk_count > 0

# 2. Чанки в OpenSearch
curl -X POST "http://localhost:9200/rag-knowledge/_search" \
  -H "Content-Type: application/json" \
  -d '{"query": {"term": {"source": "../rag-inbox/test-adr-001.md"}}}' \
  | jq '.hits.total.value, [.hits.hits[]._source.chunk_index]'
# ожидание: количество > 0, chunk_index начинается с 0

# 3. Текст чанков содержит overlap (последнее предложение предыдущего)
curl -X POST "http://localhost:9200/rag-knowledge/_search" \
  -H "Content-Type: application/json" \
  -d '{"query": {"term": {"source": "../rag-inbox/test-adr-001.md"}}, "size": 10}' \
  | jq '.hits.hits[]._source | {idx: .chunk_index, text: .text[:80]}'
```

---

## Фаза 3 — Идемпотентность (повторная индексация)

```bash
# Вызвать rag_index повторно без изменений файла
# ожидание: status="skipped", chunk_count без изменений

# Изменить файл
echo "\n\n## Обновление\nДобавлена реплика для read-нагрузки." >> ../rag-inbox/test-adr-001.md

# Вызвать rag_index снова
# ожидание: status="indexed", chunk_count мог измениться,
#           старые чанки удалены, новые добавлены

curl -X POST "http://localhost:9200/rag-knowledge/_search" \
  -H "Content-Type: application/json" \
  -d '{"query": {"term": {"source": "../rag-inbox/test-adr-001.md"}}}' \
  | jq '.hits.total.value'
# чанки не задвоились
```

---

## Фаза 4 — Автосканирование (Scheduler)

```bash
# Положить новый файл в inbox
cat > ../rag-inbox/glossary.md << 'EOF'
# Глоссарий

ADR — Architecture Decision Record, документ с фиксацией архитектурного решения.
SLO — Service Level Objective, целевой показатель надёжности сервиса.
RCA — Root Cause Analysis, анализ первопричин инцидента.
EOF

# Ждать ≤60 секунд (scheduler interval)
# Проверить что файл проиндексирован автоматически
psql -h localhost -U postgres -d leader_framework \
  -c "SELECT file_path, indexed_at FROM indexed_documents ORDER BY indexed_at DESC LIMIT 3;"
```

**Ожидание:** glossary.md появляется в таблице без ручного вызова `rag_index`.

---

## Фаза 5 — Семантический поиск (MCP: `rag_search`)

**Тест через Claude:**
```
rag_search("как принимались архитектурные решения", 3)
```

**Ожидания:**
- top-1 результат из `test-adr-001.md` (наиболее семантически близкий)
- score > 0.5
- текст содержит слова про PostgreSQL или ADR

**Тест прямым поиском через Ollama + OpenSearch:**
```bash
# Получить вектор запроса
VECTOR=$(curl -s -X POST http://localhost:11434/api/embeddings \
  -H "Content-Type: application/json" \
  -d '{"model": "multilingual-e5-large", "prompt": "архитектурные решения команды"}' \
  | jq '.embedding')

# kNN запрос
curl -X POST "http://localhost:9200/rag-knowledge/_search" \
  -H "Content-Type: application/json" \
  -d "{\"size\": 3, \"query\": {\"knn\": {\"vector\": {\"vector\": $VECTOR, \"k\": 3}}}}" \
  | jq '.hits.hits[] | {score: ._score, source: ._source.source, text: ._source.text[:100]}'
```

---

## Фаза 6 — `rag_index_directory` и `rag_status`

```bash
# Добавить несколько файлов
cp workspace/01_services/architecture/*.md ../rag-inbox/ 2>/dev/null || true

# Вызвать через MCP:
# rag_index_directory("../rag-inbox", "*.md")
# ожидание: {"indexed": N, "skipped": M, "failed": 0}

# Статус всей базы знаний:
# rag_status()
# ожидание: список всех файлов с chunk_count и indexed_at
```

---

## Фаза 7 — Негативные сценарии

| Сценарий | Действие | Ожидаемый результат |
|----------|----------|---------------------|
| Файл не найден | `rag_index("/nonexistent/path.md")` | `status: "error: File not found"` |
| Пустой файл | Файл с 0 байт | `chunk_count: 0`, без паники |
| Ollama недоступна | `ollama stop` → `rag_index(...)` | Ошибка в логах, запись в БД со status=failed |
| OpenSearch недоступна | `docker stop opensearch` → поиск | RuntimeException с понятным сообщением |
| Повторный старт сервиса | Остановить и запустить заново | Нет дублирования данных, `ensureIndexExists` не падает |

---

## Фаза 8 — Интеграция с Claude (MCP handshake)

Добавить в `.mcp.json`:
```json
{
  "mcpServers": {
    "rag": {
      "url": "http://localhost:8081/mcp"
    }
  }
}
```

Перезапустить Claude. Проверить что инструменты видны:
```
/mcp
```
**Ожидание:** 4 инструмента: `ragIndex`, `ragIndexDirectory`, `ragSearch`, `ragStatus`.

Финальный E2E тест через Claude:
```
Положи в базу знаний файл ../rag-inbox/test-adr-001.md и найди всё про PostgreSQL
```

---

## Чеклист готовности

- [ ] Сервис стартует чисто, миграция прошла
- [ ] OpenSearch index создан с `knn_vector` dimension=1024
- [ ] `rag_index` индексирует файл, чанки появляются в OS
- [ ] Повторный `rag_index` возвращает `skipped` без дублей
- [ ] Изменение файла → удаление старых чанков → новые чанки
- [ ] Scheduler подхватывает файл без ручного вызова за ≤60с
- [ ] `rag_search` возвращает семантически релевантный результат
- [ ] `rag_status` показывает все файлы с правильным `chunk_count`
- [ ] Негативные сценарии не роняют сервис
- [ ] MCP инструменты видны из Claude
