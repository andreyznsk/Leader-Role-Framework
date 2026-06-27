package ru.andreyz.memoryservice.repository;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import ru.andreyz.memoryservice.domain.UsageEvent;

import java.time.Instant;
import java.util.List;

public interface UsageEventRepository extends CrudRepository<UsageEvent, Long> {

    String SELECT_USAGE_EVENTS = """
            SELECT id,
                   event_type,
                   source,
                   status,
                   correlation_id,
                   entity_type,
                   entity_id,
                   duration_ms,
                   saved_minutes,
                   CAST(metadata_json AS VARCHAR) AS metadata_json,
                   created_at
            FROM usage_events
            """;

    @Query(SELECT_USAGE_EVENTS + " ORDER BY created_at DESC")
    List<UsageEvent> findAllByCreatedAtDesc();

    @Query(SELECT_USAGE_EVENTS + " WHERE created_at >= :from ORDER BY created_at DESC")
    List<UsageEvent> findByCreatedAtFrom(@Param("from") Instant from);

    @Query(SELECT_USAGE_EVENTS + " ORDER BY created_at DESC LIMIT :limit")
    List<UsageEvent> findRecentAll(@Param("limit") int limit);

    @Query(SELECT_USAGE_EVENTS + " WHERE created_at >= :from ORDER BY created_at DESC LIMIT :limit")
    List<UsageEvent> findRecentByCreatedAtFrom(@Param("from") Instant from, @Param("limit") int limit);
}
