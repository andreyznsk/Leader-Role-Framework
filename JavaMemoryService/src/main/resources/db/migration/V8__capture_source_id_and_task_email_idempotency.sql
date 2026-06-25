CREATE INDEX IF NOT EXISTS idx_tasks_email_id ON tasks(email_id);

ALTER TABLE captures
    ADD COLUMN IF NOT EXISTS source_id VARCHAR(500);

CREATE UNIQUE INDEX IF NOT EXISTS idx_captures_source_source_id
    ON captures(source, source_id);
