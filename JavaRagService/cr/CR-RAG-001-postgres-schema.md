# CR-RAG-001: PostgreSQL схема rag + Flyway миграция

**Дата:** 2026-06-11  
**Статус:** Done  
**Сервис:** RAG  
**Зависимости:** `infra/postgres/init.sql`

---

## Проблема / Мотивация

В RFC-rag-service.md указана БД `leader_framework`, но схема для JavaRagService
не была определена. В ARCHITECTURE.md уже есть схемы `mailagent` и `memory`,
JavaRagService должен иметь свою изолированную схему `rag` с отдельным владельцем.

---

## Решение

Добавить схему `rag` в общую БД `leader_framework`.  
Создать Flyway миграцию с таблицей `indexed_documents`.  
Добавить профиль `local` с явным указанием схемы.

---

## Изменения

### 1. infra/postgres/init.sql

Добавить в существующий файл инициализации:

```sql
-- JavaRagService
CREATE SCHEMA IF NOT EXISTS rag;
CREATE USER rag_user WITH PASSWORD 'rag_password';
GRANT USAGE ON SCHEMA rag TO rag_user;
GRANT CREATE ON SCHEMA rag TO rag_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA rag TO rag_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA rag GRANT ALL ON TABLES TO rag_user;
```

Итоговая структура БД после изменения:
```
БД: leader_framework
├── schema: mailagent   ← JavaMailAgent   (owner: mailagent_user)
├── schema: memory      ← JavaMemoryService (owner: memory_user)
└── schema: rag         ← JavaRagService   (owner: rag_user)
```

Каждому пользователю нужно поставить search_path по умолчанию на свою схему

---

### 2. Flyway миграция

Создать файл:
`JavaRagService/src/main/resources/db/migration/V1__init_rag_schema.sql`

```sql
CREATE TABLE IF NOT EXISTS indexed_documents (
    id           SERIAL PRIMARY KEY,
    file_path    TEXT        NOT NULL UNIQUE,
    file_hash    TEXT        NOT NULL,
    indexed_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    chunk_count  INT,
    status       TEXT        NOT NULL DEFAULT 'indexed'
    -- статусы: indexed | failed | outdated
);

CREATE INDEX IF NOT EXISTS idx_indexed_documents_file_path
    ON indexed_documents(file_path);

CREATE INDEX IF NOT EXISTS idx_indexed_documents_status
    ON indexed_documents(status);
```

---

### 3. application.properties (базовые настройки)

Обновить `JavaRagService/src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/leader_framework
spring.datasource.username=rag_user
spring.datasource.password=rag_password

# Flyway — только схема rag
spring.flyway.schemas=rag
spring.flyway.default-schema=rag
spring.flyway.locations=classpath:db/migration

# Все таблицы создаются в схеме rag
spring.datasource.hikari.connection-init-sql=SET search_path TO rag
```

---

### 4. application-local.properties (новый файл)

Создать `JavaRagService/src/main/resources/application-local.properties`:

```properties
# Профиль для локальной разработки на macOS M1

# PostgreSQL — локальный Docker
spring.datasource.url=jdbc:postgresql://localhost:5432/leader_framework
spring.datasource.username=rag_user
spring.datasource.password=rag_password
spring.datasource.hikari.maximum-pool-size=3
spring.datasource.hikari.connection-init-sql=SET search_path TO rag

# Flyway
spring.flyway.schemas=rag
spring.flyway.default-schema=rag

# Ollama — нативно на M1
ollama.url=http://localhost:11434
ollama.model=multilingual-e5-large

# OpenSearch — Docker
opensearch.url=http://localhost:9200
opensearch.index=rag-knowledge

# rag-inbox — относительно корня Leader-Role-Framework
rag.inbox.path=../rag-inbox
rag.scheduler.interval-ms=60000

# Логи
logging.level.ru.andreyz.ragservice=DEBUG
```

---

### 5. Запуск с профилем local

```bash
# Поднять инфраструктуру
docker compose up -d postgres opensearch

# Проверить что Ollama запущена
ollama list | grep multilingual-e5-large

# Запустить сервис из корня Leader-Role-Framework
SPRING_PROFILES_ACTIVE=local \
  java -jar JavaRagService/target/rag-service.jar

# Проверить что Flyway применил миграцию
psql -U rag_user -d leader_framework \
  -c "SELECT * FROM rag.indexed_documents LIMIT 5;"
```

---

## Как тестировать

1. `docker compose up -d postgres` — поднять PostgreSQL
2. Запустить сервис с `SPRING_PROFILES_ACTIVE=local`
3. В логах должно быть: `Flyway ... Successfully applied 1 migration to schema "rag"`
4. Проверить таблицу: `psql -c "SELECT table_name FROM information_schema.tables WHERE table_schema='rag';"`
5. Положить тестовый `.md` в `rag-inbox/` — через минуту должна появиться запись в `rag.indexed_documents`
