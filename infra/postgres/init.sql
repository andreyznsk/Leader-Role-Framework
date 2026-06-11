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

-- JavaRagService
CREATE SCHEMA IF NOT EXISTS rag;
CREATE USER rag_user WITH PASSWORD 'rag_password';

GRANT USAGE, CREATE ON SCHEMA rag TO rag_user;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA rag TO rag_user;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA rag TO rag_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA rag GRANT ALL PRIVILEGES ON TABLES TO rag_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA rag GRANT ALL PRIVILEGES ON SEQUENCES TO rag_user;

REVOKE ALL ON SCHEMA mailagent FROM rag_user;
REVOKE ALL ON SCHEMA memory    FROM rag_user;

ALTER ROLE rag_user SET search_path TO rag;
