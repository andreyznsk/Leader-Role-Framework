CREATE TABLE plugin_settings (
    plugin_code VARCHAR(64) PRIMARY KEY,
    plugin_type VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    config_json JSONB,
    secret_ref VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE plugin_heartbeats (
    plugin_code VARCHAR(64) PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    message VARCHAR(500),
    last_heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_plugin_settings_updated_at
    ON plugin_settings (updated_at);

CREATE INDEX idx_plugin_heartbeats_updated_at
    ON plugin_heartbeats (updated_at);
