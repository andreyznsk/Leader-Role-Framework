ALTER TABLE tasks ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
CREATE INDEX idx_tasks_sort_order ON tasks(plan_id, sort_order);
