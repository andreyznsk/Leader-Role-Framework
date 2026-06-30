ALTER TABLE tasks
    ADD COLUMN pending_type VARCHAR(40) NOT NULL DEFAULT 'NEW_TASK',
    ADD COLUMN suggested_task_id BIGINT,
    ADD COLUMN agent_confidence DOUBLE PRECISION,
    ADD COLUMN agent_reason TEXT,
    ADD COLUMN source_type VARCHAR(40),
    ADD COLUMN source_subject VARCHAR(500),
    ADD COLUMN source_sender VARCHAR(500),
    ADD COLUMN proposed_description_append TEXT;
