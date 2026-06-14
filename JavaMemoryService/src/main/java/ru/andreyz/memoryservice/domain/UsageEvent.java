package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("usage_events")
public record UsageEvent(
        @Id Long id,
        String eventType,
        String source,
        String status,
        String correlationId,
        String entityType,
        String entityId,
        Long durationMs,
        Integer savedMinutes,
        String metadataJson,
        Instant createdAt
) {}
