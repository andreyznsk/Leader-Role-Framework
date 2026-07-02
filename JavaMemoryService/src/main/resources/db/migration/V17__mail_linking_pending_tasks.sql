ALTER TABLE tasks ADD COLUMN pending_type VARCHAR(40) NOT NULL DEFAULT 'NEW_TASK';
ALTER TABLE tasks ADD COLUMN suggested_task_id BIGINT;
ALTER TABLE tasks ADD COLUMN agent_confidence DOUBLE PRECISION;
ALTER TABLE tasks ADD COLUMN agent_reason TEXT;
ALTER TABLE tasks ADD COLUMN source_type VARCHAR(40);
ALTER TABLE tasks ADD COLUMN source_subject VARCHAR(500);
ALTER TABLE tasks ADD COLUMN source_sender VARCHAR(500);
ALTER TABLE tasks ADD COLUMN proposed_description_append TEXT;
