CREATE TABLE control_settings_audit (
    id BIGSERIAL PRIMARY KEY,
    config_version BIGINT NOT NULL,
    changed_keys_json JSONB NOT NULL,
    request_json JSONB NOT NULL,
    applied_json JSONB NOT NULL,
    status VARCHAR(64) NOT NULL,
    message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
