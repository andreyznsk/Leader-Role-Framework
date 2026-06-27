package ru.andreyz.memoryservice.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.andreyz.memoryservice.dto.ControlPluginAuditDto;
import ru.andreyz.memoryservice.dto.ControlPluginDto;
import ru.andreyz.memoryservice.dto.ControlPluginSettingsResponse;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ControlPluginStore {

    private final JdbcClient jdbcClient;
    private final PluginSettingsStore pluginSettingsStore;

    public ControlPluginStore(JdbcClient jdbcClient, PluginSettingsStore pluginSettingsStore) {
        this.jdbcClient = jdbcClient;
        this.pluginSettingsStore = pluginSettingsStore;
    }

    public void upsertPlugin(String code, String name, String baseUrl, boolean enabled) {
        int updated = jdbcClient.sql("""
                UPDATE control_plugins
                SET name = :name,
                    base_url = :baseUrl,
                    enabled = :enabled,
                    updated_at = CURRENT_TIMESTAMP
                WHERE code = :code
                """)
                .param("code", normalize(code))
                .param("name", name)
                .param("baseUrl", baseUrl)
                .param("enabled", enabled)
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO control_plugins (code, name, base_url, enabled, status, created_at, updated_at)
                    VALUES (:code, :name, :baseUrl, :enabled, 'UNKNOWN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)
                    .param("code", normalize(code))
                    .param("name", name)
                    .param("baseUrl", baseUrl)
                    .param("enabled", enabled)
                    .update();
        }
    }

    public List<ControlPluginDto> findAllPlugins() {
        return jdbcClient.sql("""
                SELECT code, name, base_url, enabled, status, last_sync_at
                FROM control_plugins
                ORDER BY code
                """)
                .query(this::mapPlugin)
                .list();
    }

    public Optional<ControlPluginDto> findPlugin(String code) {
        return jdbcClient.sql("""
                SELECT code, name, base_url, enabled, status, last_sync_at
                FROM control_plugins
                WHERE code = :code
                """)
                .param("code", normalize(code))
                .query(this::mapPlugin)
                .optional();
    }

    public void updatePluginStatus(String code, String status, Instant lastSyncAt) {
        jdbcClient.sql("""
                UPDATE control_plugins
                SET status = :status,
                    last_sync_at = :lastSyncAt,
                    updated_at = CURRENT_TIMESTAMP
                WHERE code = :code
                """)
                .param("code", normalize(code))
                .param("status", status)
                .param("lastSyncAt", lastSyncAt != null ? java.sql.Timestamp.from(lastSyncAt) : null)
                .update();
    }

    public void saveSnapshot(String pluginCode,
                             ControlPluginSettingsResponse descriptor,
                             String descriptorJson) {
        int updated = jdbcClient.sql("""
                UPDATE control_plugin_settings_snapshot
                SET descriptor_json = %s,
                    config_version = :configVersion,
                    synced_at = CURRENT_TIMESTAMP
                WHERE plugin_code = :pluginCode
                """.formatted(jsonExpression("descriptorJson")))
                .param("pluginCode", normalize(pluginCode))
                .param("descriptorJson", descriptorJson)
                .param("configVersion", descriptor.version())
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                    INSERT INTO control_plugin_settings_snapshot (plugin_code, descriptor_json, config_version, synced_at)
                    VALUES (:pluginCode, %s, :configVersion, CURRENT_TIMESTAMP)
                    """.formatted(jsonExpression("descriptorJson")))
                    .param("pluginCode", normalize(pluginCode))
                    .param("descriptorJson", descriptorJson)
                    .param("configVersion", descriptor.version())
                    .update();
        }
    }

    public Optional<String> findSnapshotJson(String pluginCode) {
        return jdbcClient.sql("""
                SELECT descriptor_json
                FROM control_plugin_settings_snapshot
                WHERE plugin_code = :pluginCode
                """)
                .param("pluginCode", normalize(pluginCode))
                .query((rs, rowNum) -> rs.getString("descriptor_json"))
                .optional();
    }

    public void saveAudit(String pluginCode,
                          String action,
                          String requestJson,
                          String responseJson,
                          String status,
                          String message) {
        jdbcClient.sql("""
                INSERT INTO control_plugin_audit (
                    plugin_code,
                    action,
                    request_json,
                    response_json,
                    status,
                    message,
                    created_at
                ) VALUES (
                    :pluginCode,
                    :action,
                    %s,
                    %s,
                    :status,
                    :message,
                    CURRENT_TIMESTAMP
                )
                """.formatted(jsonExpression("requestJson"), jsonExpression("responseJson")))
                .param("pluginCode", normalize(pluginCode))
                .param("action", action)
                .param("requestJson", requestJson)
                .param("responseJson", responseJson)
                .param("status", status)
                .param("message", message)
                .update();
    }

    public List<ControlPluginAuditDto> findAudit(String pluginCode) {
        return jdbcClient.sql("""
                SELECT plugin_code, action, status, message, request_json, response_json, created_at
                FROM control_plugin_audit
                WHERE plugin_code = :pluginCode
                ORDER BY created_at DESC, id DESC
                LIMIT 50
                """)
                .param("pluginCode", normalize(pluginCode))
                .query(this::mapAudit)
                .list();
    }

    private ControlPluginDto mapPlugin(ResultSet rs, int rowNum) throws SQLException {
        return new ControlPluginDto(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("base_url"),
                rs.getBoolean("enabled"),
                rs.getString("status"),
                rs.getTimestamp("last_sync_at") != null ? rs.getTimestamp("last_sync_at").toInstant() : null
        );
    }

    private ControlPluginAuditDto mapAudit(ResultSet rs, int rowNum) throws SQLException {
        return new ControlPluginAuditDto(
                rs.getString("plugin_code"),
                rs.getString("action"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getString("request_json"),
                rs.getString("response_json"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private String normalize(String code) {
        return code == null ? "" : code.toLowerCase();
    }

    private String jsonExpression(String paramName) {
        return pluginSettingsStore.jsonExpression().replace(":configJson", ":" + paramName);
    }
}
