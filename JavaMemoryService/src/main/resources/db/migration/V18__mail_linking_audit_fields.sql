ALTER TABLE tasks
    ADD COLUMN linked_to_task_id BIGINT,
    ADD COLUMN linked_at TIMESTAMP;
