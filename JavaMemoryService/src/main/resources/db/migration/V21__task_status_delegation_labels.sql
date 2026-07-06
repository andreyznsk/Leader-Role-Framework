ALTER TABLE tasks
    ADD COLUMN assigned_person_id BIGINT REFERENCES people(id);

CREATE INDEX idx_tasks_assigned_person_id ON tasks(assigned_person_id);

CREATE TABLE task_labels (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL UNIQUE,
    color      VARCHAR(50),
    archived   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE task_label_mapping (
    task_id   BIGINT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    label_id  BIGINT NOT NULL REFERENCES task_labels(id),
    PRIMARY KEY (task_id, label_id)
);

CREATE INDEX idx_task_label_mapping_label_id ON task_label_mapping(label_id);
