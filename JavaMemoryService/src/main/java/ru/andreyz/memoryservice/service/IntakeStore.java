package ru.andreyz.memoryservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import ru.andreyz.memoryservice.domain.IntakeItem;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Repository
public class IntakeStore {

    private final JdbcClient jdbcClient;
    private final boolean postgres;

    public IntakeStore(JdbcClient jdbcClient, DataSource dataSource) {
        this.jdbcClient = jdbcClient;
        this.postgres = isPostgres(dataSource);
    }

    public IntakeItem save(IntakeItem item) {
        int updated = jdbcClient.sql("""
                        UPDATE intake_items
                        SET source_type = :sourceType,
                            source_id = :sourceId,
                            source_payload_json = %s,
                            source_text = :sourceText,
                            agent_provider = :agentProvider,
                            agent_prompt = :agentPrompt,
                            agent_result_json = %s,
                            agent_result_text = :agentResultText,
                            suggested_route = :suggestedRoute,
                            suggested_payload_json = %s,
                            final_route = :finalRoute,
                            final_payload_json = %s,
                            status = :status,
                            confidence = :confidence,
                            created_by = :createdBy,
                            reviewed_by = :reviewedBy,
                            created_at = :createdAt,
                            reviewed_at = :reviewedAt,
                            applied_at = :appliedAt,
                            rejected_at = :rejectedAt,
                            reject_reason = :rejectReason
                        WHERE id = :id
                        """.formatted(jsonExpression("sourcePayloadJson"),
                jsonExpression("agentResultJson"),
                jsonExpression("suggestedPayloadJson"),
                jsonExpression("finalPayloadJson")))
                .param("id", item.id())
                .param("sourceType", item.sourceType())
                .param("sourceId", item.sourceId())
                .param("sourcePayloadJson", item.sourcePayloadJson())
                .param("sourceText", item.sourceText())
                .param("agentProvider", item.agentProvider())
                .param("agentPrompt", item.agentPrompt())
                .param("agentResultJson", item.agentResultJson())
                .param("agentResultText", item.agentResultText())
                .param("suggestedRoute", item.suggestedRoute())
                .param("suggestedPayloadJson", item.suggestedPayloadJson())
                .param("finalRoute", item.finalRoute())
                .param("finalPayloadJson", item.finalPayloadJson())
                .param("status", item.status())
                .param("confidence", item.confidence())
                .param("createdBy", item.createdBy())
                .param("reviewedBy", item.reviewedBy())
                .param("createdAt", toTimestamp(item.createdAt()))
                .param("reviewedAt", toTimestamp(item.reviewedAt()))
                .param("appliedAt", toTimestamp(item.appliedAt()))
                .param("rejectedAt", toTimestamp(item.rejectedAt()))
                .param("rejectReason", item.rejectReason())
                .update();
        if (updated == 0) {
            jdbcClient.sql("""
                            INSERT INTO intake_items (
                                id, source_type, source_id, source_payload_json, source_text,
                                agent_provider, agent_prompt, agent_result_json, agent_result_text,
                                suggested_route, suggested_payload_json, final_route, final_payload_json,
                                status, confidence, created_by, reviewed_by, created_at,
                                reviewed_at, applied_at, rejected_at, reject_reason
                            )
                            VALUES (
                                :id, :sourceType, :sourceId, %s, :sourceText,
                                :agentProvider, :agentPrompt, %s, :agentResultText,
                                :suggestedRoute, %s, :finalRoute, %s,
                                :status, :confidence, :createdBy, :reviewedBy, :createdAt,
                                :reviewedAt, :appliedAt, :rejectedAt, :rejectReason
                            )
                            """.formatted(jsonExpression("sourcePayloadJson"),
                    jsonExpression("agentResultJson"),
                    jsonExpression("suggestedPayloadJson"),
                    jsonExpression("finalPayloadJson")))
                    .param("id", item.id())
                    .param("sourceType", item.sourceType())
                    .param("sourceId", item.sourceId())
                    .param("sourcePayloadJson", item.sourcePayloadJson())
                    .param("sourceText", item.sourceText())
                    .param("agentProvider", item.agentProvider())
                    .param("agentPrompt", item.agentPrompt())
                    .param("agentResultJson", item.agentResultJson())
                    .param("agentResultText", item.agentResultText())
                    .param("suggestedRoute", item.suggestedRoute())
                    .param("suggestedPayloadJson", item.suggestedPayloadJson())
                    .param("finalRoute", item.finalRoute())
                    .param("finalPayloadJson", item.finalPayloadJson())
                    .param("status", item.status())
                    .param("confidence", item.confidence())
                    .param("createdBy", item.createdBy())
                    .param("reviewedBy", item.reviewedBy())
                    .param("createdAt", toTimestamp(item.createdAt()))
                    .param("reviewedAt", toTimestamp(item.reviewedAt()))
                    .param("appliedAt", toTimestamp(item.appliedAt()))
                    .param("rejectedAt", toTimestamp(item.rejectedAt()))
                    .param("rejectReason", item.rejectReason())
                    .update();
        }
        return findById(item.id()).orElseThrow();
    }

    public Optional<IntakeItem> findById(UUID id) {
        return jdbcClient.sql("""
                        SELECT id, source_type, source_id, source_payload_json, source_text,
                               agent_provider, agent_prompt, agent_result_json, agent_result_text,
                               suggested_route, suggested_payload_json, final_route, final_payload_json,
                               status, confidence, created_by, reviewed_by, created_at,
                               reviewed_at, applied_at, rejected_at, reject_reason
                        FROM intake_items
                        WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapItem)
                .optional();
    }

    public List<IntakeItem> findAll() {
        return jdbcClient.sql("""
                        SELECT id, source_type, source_id, source_payload_json, source_text,
                               agent_provider, agent_prompt, agent_result_json, agent_result_text,
                               suggested_route, suggested_payload_json, final_route, final_payload_json,
                               status, confidence, created_by, reviewed_by, created_at,
                               reviewed_at, applied_at, rejected_at, reject_reason
                        FROM intake_items
                        """)
                .query(this::mapItem)
                .list();
    }

    private IntakeItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new IntakeItem(
                UUID.fromString(rs.getString("id")),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getString("source_payload_json"),
                rs.getString("source_text"),
                rs.getString("agent_provider"),
                rs.getString("agent_prompt"),
                rs.getString("agent_result_json"),
                rs.getString("agent_result_text"),
                rs.getString("suggested_route"),
                rs.getString("suggested_payload_json"),
                rs.getString("final_route"),
                rs.getString("final_payload_json"),
                rs.getString("status"),
                rs.getBigDecimal("confidence"),
                rs.getString("created_by"),
                rs.getString("reviewed_by"),
                toInstant(rs, "created_at"),
                toInstant(rs, "reviewed_at"),
                toInstant(rs, "applied_at"),
                toInstant(rs, "rejected_at"),
                rs.getString("reject_reason")
        );
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp != null ? timestamp.toInstant() : null;
    }

    private Timestamp toTimestamp(Instant instant) {
        return instant != null ? Timestamp.from(instant) : null;
    }

    private String jsonExpression(String paramName) {
        return postgres ? "CAST(:" + paramName + " AS jsonb)" : ":" + paramName;
    }

    private boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            log.warn("Failed to detect database type for intake store: {}", e.getMessage());
            log.error("", e);
            return false;
        }
    }
}
