-- CR-MEM-009: PostgreSQL GIN indexes for Global Search performance.
-- Apply manually when full-text search performance becomes a concern at scale.
-- Do NOT add to Flyway migrations (H2 does not support USING GIN / to_tsvector).

CREATE INDEX IF NOT EXISTS idx_tasks_search_text
    ON tasks USING gin (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, '')));

CREATE INDEX IF NOT EXISTS idx_risks_search_text
    ON risks USING gin (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(mitigation, '')));

CREATE INDEX IF NOT EXISTS idx_incidents_search_text
    ON incidents USING gin (to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, '') || ' ' || coalesce(root_cause, '')));

CREATE INDEX IF NOT EXISTS idx_people_search_text
    ON people USING gin (to_tsvector('simple', coalesce(full_name, '') || ' ' || coalesce(domain, '') || ' ' || coalesce(notes, '')));

CREATE INDEX IF NOT EXISTS idx_people_notes_search_text
    ON people_notes USING gin (to_tsvector('simple', coalesce(note, '')));
