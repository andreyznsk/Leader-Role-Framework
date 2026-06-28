-- CR-MEM-012: CaptureBot UI - state machine and new columns
ALTER TABLE captures ADD COLUMN IF NOT EXISTS route          VARCHAR(64);
ALTER TABLE captures ADD COLUMN IF NOT EXISTS target_type    VARCHAR(64);
ALTER TABLE captures ADD COLUMN IF NOT EXISTS target_id      VARCHAR(128);
ALTER TABLE captures ADD COLUMN IF NOT EXISTS target_ref     TEXT;
ALTER TABLE captures ADD COLUMN IF NOT EXISTS file_path      TEXT;
ALTER TABLE captures ADD COLUMN IF NOT EXISTS error_message  TEXT;
ALTER TABLE captures ADD COLUMN IF NOT EXISTS updated_at     TIMESTAMP NOT NULL DEFAULT NOW();
ALTER TABLE captures ADD COLUMN IF NOT EXISTS archived_at    TIMESTAMP;

-- Rename existing status PENDING → NEW
UPDATE captures SET status = 'NEW' WHERE status = 'PENDING';

-- Additional indexes for UI filtering and scheduler query
CREATE INDEX IF NOT EXISTS idx_captures_status_captured_at ON captures(status, captured_at);
CREATE INDEX IF NOT EXISTS idx_captures_route              ON captures(route);
CREATE INDEX IF NOT EXISTS idx_captures_source             ON captures(source);
