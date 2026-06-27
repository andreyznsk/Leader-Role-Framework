ALTER TABLE mailagent.processed_emails
    ADD COLUMN IF NOT EXISTS response_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'PROCESSED',
    ADD COLUMN IF NOT EXISTS failed_route VARCHAR(64) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS route_payload_json TEXT,
    ADD COLUMN IF NOT EXISTS action_result_json TEXT,
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS attempts_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS first_seen_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT NOW();

UPDATE mailagent.processed_emails
SET response_type = COALESCE(response_type, agent_type),
    status = COALESCE(status, 'PROCESSED'),
    failed_route = COALESCE(failed_route, 'NONE'),
    attempts_count = COALESCE(attempts_count, 0),
    first_seen_at = COALESCE(first_seen_at, processed_at, created_at),
    last_attempt_at = COALESCE(last_attempt_at, processed_at, updated_at),
    created_at = COALESCE(created_at, processed_at, NOW()),
    updated_at = COALESCE(updated_at, processed_at, NOW())
WHERE response_type IS NULL
   OR first_seen_at IS NULL
   OR last_attempt_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_processed_emails_status_attempt
    ON mailagent.processed_emails(status, last_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_processed_emails_failed_route
    ON mailagent.processed_emails(failed_route);
