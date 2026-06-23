ALTER TABLE control_plugin_settings_snapshot
    ALTER COLUMN descriptor_json VARCHAR;

ALTER TABLE control_plugin_audit
    ALTER COLUMN request_json VARCHAR;

ALTER TABLE control_plugin_audit
    ALTER COLUMN response_json VARCHAR;
