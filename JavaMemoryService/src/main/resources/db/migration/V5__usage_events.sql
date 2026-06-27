CREATE TABLE usage_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(128),
    entity_type VARCHAR(64),
    entity_id VARCHAR(128),
    duration_ms BIGINT,
    saved_minutes INTEGER NOT NULL DEFAULT 0,
    metadata_json JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_events_created_at
    ON usage_events (created_at);

CREATE INDEX idx_usage_events_event_type_created_at
    ON usage_events (event_type, created_at);

CREATE INDEX idx_usage_events_source_created_at
    ON usage_events (source, created_at);

CREATE INDEX idx_usage_events_correlation_id
    ON usage_events (correlation_id);
