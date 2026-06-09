CREATE TABLE IF NOT EXISTS indexed_documents (
    id          SERIAL PRIMARY KEY,
    file_path   TEXT        NOT NULL UNIQUE,
    file_hash   TEXT        NOT NULL,
    indexed_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    chunk_count INT,
    status      TEXT        NOT NULL DEFAULT 'indexed'
);

CREATE INDEX IF NOT EXISTS idx_indexed_documents_file_path ON indexed_documents(file_path);
CREATE INDEX IF NOT EXISTS idx_indexed_documents_status    ON indexed_documents(status);
