CREATE TABLE control_plugins (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    last_sync_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE control_plugin_settings_snapshot (
    id BIGSERIAL PRIMARY KEY,
    plugin_code VARCHAR(64) NOT NULL,
    descriptor_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    config_version BIGINT NOT NULL DEFAULT 1,
    synced_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE(plugin_code)
);

CREATE TABLE control_plugin_audit (
    id BIGSERIAL PRIMARY KEY,
    plugin_code VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    request_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL,
    message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
