package ru.andreyz.memoryservice.dto;

import ru.andreyz.memoryservice.domain.UsageEventType;

import java.util.Map;

public record UsageEventRequest(
        UsageEventType eventType,
        String source,
        String status,
        String correlationId,
        String entityType,
        String entityId,
        Long durationMs,
        Integer savedMinutes,
        Map<String, Object> metadata
) {}
