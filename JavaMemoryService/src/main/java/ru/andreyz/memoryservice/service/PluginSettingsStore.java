package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Repository
public class PluginSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(PluginSettingsStore.class);

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final boolean postgres;

    public PluginSettingsStore(JdbcClient jdbcClient, ObjectMapper objectMapper, DataSource dataSource) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.postgres = isPostgres(dataSource);
    }

    public List<StoredPluginSettings> findAllSettings() {
        return jdbcClient.sql("""
                        SELECT plugin_code, plugin_type, enabled, config_json, secret_ref, created_at, updated_at
                        FROM plugin_settings
                        ORDER BY plugin_code
                        """)
                .query(this::mapSettings)
                .list();
    }

    public Optional<StoredPluginSettings> findSettings(String pluginCode) {
        return jdbcClient.sql("""
                        SELECT plugin_code, plugin_type, enabled, config_json, secret_ref, created_at, updated_at
                        FROM plugin_settings
                        WHERE plugin_code = :pluginCode
                        """)
                .param("pluginCode", normalizeCode(pluginCode))
                .query(this::mapSettings)
                .optional();
    }

    public StoredPluginSettings saveSettings(String pluginCode,
                                             String pluginType,
                                             boolean enabled,
                                             Map<String, Object> config,
                                             String secretRef) {
        String configJson = writeJson(config);
        String normalizedCode = normalizeCode(pluginCode);
        int updated = jdbcClient.sql("""
                        UPDATE plugin_settings
                        SET plugin_type = :pluginType,
                            enabled = :enabled,
                            config_json = %s,
                            secret_ref = :secretRef,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE plugin_code = :pluginCode
                        """.formatted(configExpression()))
                .param("pluginCode", normalizedCode)
                .param("pluginType", pluginType)
                .param("enabled", enabled)
                .param("configJson", configJson)
                .param("secretRef", blankToNull(secretRef))
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO plugin_settings (plugin_code, plugin_type, enabled, config_json, secret_ref, created_at, updated_at)
                            VALUES (:pluginCode, :pluginType, :enabled, %s, :secretRef, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """.formatted(configExpression()))
                    .param("pluginCode", normalizedCode)
                    .param("pluginType", pluginType)
                    .param("enabled", enabled)
                    .param("configJson", configJson)
                    .param("secretRef", blankToNull(secretRef))
                    .update();
        }
        return findSettings(pluginCode).orElseThrow();
    }

    public List<StoredPluginHeartbeat> findAllHeartbeats() {
        return jdbcClient.sql("""
                        SELECT plugin_code, status, message, last_heartbeat_at, updated_at
                        FROM plugin_heartbeats
                        ORDER BY plugin_code
                        """)
                .query(this::mapHeartbeat)
                .list();
    }

    public Optional<StoredPluginHeartbeat> findHeartbeat(String pluginCode) {
        return jdbcClient.sql("""
                        SELECT plugin_code, status, message, last_heartbeat_at, updated_at
                        FROM plugin_heartbeats
                        WHERE plugin_code = :pluginCode
                        """)
                .param("pluginCode", normalizeCode(pluginCode))
                .query(this::mapHeartbeat)
                .optional();
    }

    public StoredPluginHeartbeat saveHeartbeat(String pluginCode, String status, String message) {
        String normalizedCode = normalizeCode(pluginCode);
        int updated = jdbcClient.sql("""
                        UPDATE plugin_heartbeats
                        SET status = :status,
                            message = :message,
                            last_heartbeat_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE plugin_code = :pluginCode
                        """)
                .param("pluginCode", normalizedCode)
                .param("status", normalizeStatus(status))
                .param("message", blankToNull(message))
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO plugin_heartbeats (plugin_code, status, message, last_heartbeat_at, updated_at)
                            VALUES (:pluginCode, :status, :message, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """)
                    .param("pluginCode", normalizedCode)
                    .param("status", normalizeStatus(status))
                    .param("message", blankToNull(message))
                    .update();
        }
        return findHeartbeat(pluginCode).orElseThrow();
    }

    private String configExpression() {
        return postgres ? "CAST(:configJson AS jsonb)" : ":configJson";
    }

    private StoredPluginSettings mapSettings(ResultSet rs, int rowNum) throws SQLException {
        return new StoredPluginSettings(
                rs.getString("plugin_code"),
                rs.getString("plugin_type"),
                rs.getBoolean("enabled"),
                readJsonMap(rs.getString("config_json")),
                rs.getString("secret_ref"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private StoredPluginHeartbeat mapHeartbeat(ResultSet rs, int rowNum) throws SQLException {
        return new StoredPluginHeartbeat(
                rs.getString("plugin_code"),
                rs.getString("status"),
                rs.getString("message"),
                rs.getTimestamp("last_heartbeat_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructMapType(HashMap.class, String.class, Object.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse plugin config JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String writeJson(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize plugin config", e);
        }
    }

    private boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            log.warn("Failed to detect database type for plugin settings: {}", e.getMessage());
            return false;
        }
    }

    private String normalizeCode(String pluginCode) {
        return pluginCode == null ? "" : pluginCode.toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "UNKNOWN" : status.toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record StoredPluginSettings(
            String pluginCode,
            String pluginType,
            boolean enabled,
            Map<String, Object> config,
            String secretRef,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record StoredPluginHeartbeat(
            String pluginCode,
            String status,
            String message,
            Instant lastHeartbeatAt,
            Instant updatedAt
    ) {
    }
}
