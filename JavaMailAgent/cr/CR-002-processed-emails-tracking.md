# CR-002: Processed Emails Tracking + Multi-Folder Scan + PostgreSQL Setup

**Дата:** 2026-06-06  
**Статус:** Draft  
**Сервис:** JavaMailAgent + JavaMemoryService + Infrastructure  
**Зависимости:** CR-001 (опционально)

---

## Проблема / Мотивация

1. Шедулер повторно обрабатывает уже обработанные письма — нет локального
   трекинга. `markAsRead` на сервере нежелателен для `REQUEST` и `DRAFT`.
2. Письма рассортированы по папкам — поллинг только `INBOX` пропускает их.
3. Нет единой PostgreSQL инфраструктуры — нужна БД с изолированными схемами
   для каждого сервиса.

---

## Решение

### 1. Таблица `processed_emails` в схеме `mailagent`
`JavaMailAgent` пишет в свою схему напрямую через Spring Data JDBC + Flyway.

### 2. `markAsRead` только для NOISE
`REQUEST` и `DRAFT` остаются непрочитанными в почте.

### 3. Сканирование всех папок с исключениями
Читаем все папки сервера, пропускаем те что в `mail.folders.exclude`.

### 4. Один PostgreSQL, две схемы
```
БД: leader_framework
├── schema: mailagent   ← JavaMailAgent (владелец: mailagent_user)
└── schema: memory      ← JavaMemoryService (владелец: memory_user)
```
Каждый сервис управляет только своей схемой через Flyway.

---

## Инфраструктура

### docker-compose.yml (корень Leader-Role-Framework)

```yaml
services:
  postgres:
    image: postgres:16
    container_name: leader-postgres
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: leader_framework
      POSTGRES_USER: superuser
      POSTGRES_PASSWORD: ${POSTGRES_SUPERUSER_PASSWORD:-superpassword}
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./infra/postgres/init.sql:/docker-entrypoint-initdb.d/init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U superuser -d leader_framework"]
      interval: 10s
      timeout: 5s
      retries: 5

  opensearch:
    image: opensearchproject/opensearch:2
    container_name: leader-opensearch
    ports:
      - "9200:9200"
    environment:
      - discovery.type=single-node
      - DISABLE_SECURITY_PLUGIN=true
    volumes:
      - opensearch_data:/usr/share/opensearch/data

  opensearch-dashboards:
    image: opensearchproject/opensearch-dashboards:2
    container_name: leader-opensearch-dashboards
    ports:
      - "5601:5601"
    environment:
      - OPENSEARCH_HOSTS=http://opensearch:9200
      - DISABLE_SECURITY_DASHBOARDS_PLUGIN=true
    depends_on:
      - opensearch

  maildev:
    image: maildev/maildev:latest
    container_name: leader-maildev
    ports:
      - "1080:1080"
      - "1025:1025"
    profiles:
      - local

volumes:
  postgres_data:
  opensearch_data:
```

---

### infra/postgres/init.sql

```sql
-- =====================================================
-- Leader-Role-Framework — PostgreSQL initialization
-- =====================================================

CREATE SCHEMA IF NOT EXISTS mailagent;
CREATE SCHEMA IF NOT EXISTS memory;

CREATE USER mailagent_user WITH PASSWORD 'mailagent_password';
CREATE USER memory_user WITH PASSWORD 'memory_password';

GRANT USAGE, CREATE ON SCHEMA mailagent TO mailagent_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA mailagent TO mailagent_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA mailagent TO mailagent_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA mailagent GRANT ALL PRIVILEGES ON TABLES TO mailagent_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA mailagent GRANT ALL PRIVILEGES ON SEQUENCES TO mailagent_user;

GRANT USAGE, CREATE ON SCHEMA memory TO memory_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA memory TO memory_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA memory TO memory_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA memory GRANT ALL PRIVILEGES ON TABLES TO memory_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA memory GRANT ALL PRIVILEGES ON SEQUENCES TO memory_user;

REVOKE ALL ON SCHEMA mailagent FROM memory_user;
REVOKE ALL ON SCHEMA memory FROM mailagent_user;

ALTER ROLE mailagent_user SET search_path TO mailagent;
ALTER ROLE memory_user    SET search_path TO memory;
```

---

### .env.example
```properties
POSTGRES_SUPERUSER_PASSWORD=superpassword
POSTGRES_MAILAGENT_PASSWORD=mailagent_password
POSTGRES_MEMORY_PASSWORD=memory_password
```

---

## Изменения в JavaMailAgent

### application.properties — добавить
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/leader_framework
spring.datasource.username=mailagent_user
spring.datasource.password=${POSTGRES_MAILAGENT_PASSWORD:mailagent_password}
spring.flyway.schemas=mailagent
spring.flyway.default-schema=mailagent
spring.flyway.locations=classpath:db/migration

mail.folders.exclude=Sent,Drafts,Trash,Spam,Archive,Junk,Deleted Items
```

### Flyway миграция V1__create_processed_emails.sql
```sql
CREATE TABLE IF NOT EXISTS mailagent.processed_emails (
    id            BIGSERIAL PRIMARY KEY,
    email_id      VARCHAR(512) NOT NULL UNIQUE,
    folder        VARCHAR(255),
    sender        VARCHAR(255),
    subject       VARCHAR(512),
    agent_type    VARCHAR(16) NOT NULL,
    processed_at  TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_processed_emails_email_id ON mailagent.processed_emails(email_id);
CREATE INDEX idx_processed_emails_processed_at ON mailagent.processed_emails(processed_at);
```

### Новые классы
- `model/ProcessedEmail.java` — Spring Data JDBC record с @Table("mailagent.processed_emails")
- `repository/ProcessedEmailRepository.java` — CrudRepository с existsByEmailId()

### Изменения в MailAgentJob
- Цикл по папкам через mailClient.listFolders(excludeList)
- Проверка existsByEmailId перед обработкой
- markAsRead только для NOISE
- Запись в processed_emails после успешной обработки

### Изменения в MailClient интерфейсе
- listFolders(List<String> excludeFolders)
- listUnread(String folder, int limit)
- markAsRead(String emailId, String folder)
