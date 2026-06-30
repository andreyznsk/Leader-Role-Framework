package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.UsageEvent;
import ru.andreyz.memoryservice.dto.UsageEventCommand;
import ru.andreyz.memoryservice.dto.UsageEventDto;
import ru.andreyz.memoryservice.repository.UsageEventRepository;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UsageEventService {


    private final UsageEventRepository usageEventRepository;
    private final UsageSavedTimePolicy savedTimePolicy;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    private final boolean postgres;

    public UsageEventService(UsageEventRepository usageEventRepository,
                             UsageSavedTimePolicy savedTimePolicy,
                             ObjectMapper objectMapper,
                             JdbcClient jdbcClient,
                             DataSource dataSource) {
        this.usageEventRepository = usageEventRepository;
        this.savedTimePolicy = savedTimePolicy;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
        this.postgres = isPostgres(dataSource);
    }

    public void record(UsageEventCommand command) {
        try {
            if (command == null || command.eventType() == null) {
                return;
            }
            int savedMinutes = command.savedMinutes() != null
                    ? command.savedMinutes()
                    : savedTimePolicy.defaultSavedMinutes(command.eventType());
            insertEvent(command, savedMinutes, metadataJson(command));
        } catch (Exception e) {
            log.warn("Failed to record usage event {}: {}", command != null ? command.eventType() : null, e.getMessage(), e);
        }
    }

    public List<UsageEventDto> getRecentEvents(UsageStatsPeriod period, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        Instant from = period.startInstant();
        List<UsageEvent> events = from == null
                ? usageEventRepository.findRecentAll(boundedLimit)
                : usageEventRepository.findRecentByCreatedAtFrom(from, boundedLimit);
        return events.stream()
                .map(this::toDto)
                .toList();
    }

    private UsageEventDto toDto(UsageEvent event) {
        return new UsageEventDto(
                event.id(),
                event.eventType(),
                event.source(),
                event.status(),
                event.correlationId(),
                event.entityType(),
                event.entityId(),
                event.durationMs(),
                event.savedMinutes(),
                event.metadataJson(),
                event.createdAt()
        );
    }

    private String metadataJson(UsageEventCommand command) throws JsonProcessingException {
        if (command.metadata() == null || command.metadata().isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(command.metadata());
    }

    private void insertEvent(UsageEventCommand command, int savedMinutes, String metadataJson) {
        String metadataExpr = postgres ? "CAST(:metadataJson AS jsonb)" : ":metadataJson";
        jdbcClient.sql("""
                        INSERT INTO usage_events (
                            event_type, source, status, correlation_id, entity_type, entity_id,
                            duration_ms, saved_minutes, metadata_json
                        )
                        VALUES (
                            :eventType, :source, :status, :correlationId, :entityType, :entityId,
                            :durationMs, :savedMinutes, %s
                        )
                        """.formatted(metadataExpr))
                .param("eventType", command.eventType().name())
                .param("source", normalizeSource(command.source()))
                .param("status", normalizeStatus(command.status()))
                .param("correlationId", blankToNull(command.correlationId()))
                .param("entityType", blankToNull(command.entityType()))
                .param("entityId", blankToNull(command.entityId()))
                .param("durationMs", command.durationMs())
                .param("savedMinutes", savedMinutes)
                .param("metadataJson", metadataJson)
                .update();
    }

    private String normalizeSource(String source) {
        return source == null || source.isBlank() ? "memory-service" : source;
    }

    private String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "SUCCESS" : status;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres");
        } catch (SQLException e) {
            log.warn("Failed to detect database type for usage events: {}", e.getMessage());
            log.error("", e);
            return false;
        }
    }
}
