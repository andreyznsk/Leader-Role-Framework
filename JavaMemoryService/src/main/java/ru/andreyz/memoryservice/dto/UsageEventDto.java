package ru.andreyz.memoryservice.dto;

import java.time.Instant;

public record UsageEventDto(
        Long id,
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
