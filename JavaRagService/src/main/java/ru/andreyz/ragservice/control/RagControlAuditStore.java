package ru.andreyz.ragservice.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class RagControlAuditStore {


    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public RagControlAuditStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public void save(long configVersion,
                     List<String> changedKeys,
                     Map<String, String> request,
                     Map<String, String> applied,
                     String status,
                     String message) {
        jdbcClient.sql("""
                INSERT INTO control_settings_audit (
                    config_version,
                    changed_keys_json,
                    request_json,
                    applied_json,
                    status,
                    message,
                    created_at
                ) VALUES (
                    :configVersion,
                    CAST(:changedKeysJson AS jsonb),
                    CAST(:requestJson AS jsonb),
                    CAST(:appliedJson AS jsonb),
                    :status,
                    :message,
                    CURRENT_TIMESTAMP
                )
                """)
                .param("configVersion", configVersion)
                .param("changedKeysJson", writeJson(changedKeys))
                .param("requestJson", writeJson(request))
                .param("appliedJson", writeJson(applied))
                .param("status", status)
                .param("message", message)
                .update();
    }

    public List<ControlAuditEntry> findRecent() {
        return jdbcClient.sql("""
                SELECT created_at, status, changed_keys_json, message
                FROM control_settings_audit
                ORDER BY created_at DESC, id DESC
                LIMIT 50
                """)
                .query(this::mapAuditEntry)
                .list();
    }

    private ControlAuditEntry mapAuditEntry(ResultSet rs, int rowNum) throws SQLException {
        return new ControlAuditEntry(
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getString("status"),
                readStringList(rs.getString("changed_keys_json")),
                rs.getString("message")
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize control audit payload", e);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            log.error("", e);
            return List.of();
        }
    }
}
