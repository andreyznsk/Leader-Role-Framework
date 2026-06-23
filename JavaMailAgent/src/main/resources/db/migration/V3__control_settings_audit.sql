CREATE TABLE control_settings_audit (
    id BIGSERIAL PRIMARY KEY,
    config_version BIGINT NOT NULL,
    changed_keys_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    applied_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL,
    message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
