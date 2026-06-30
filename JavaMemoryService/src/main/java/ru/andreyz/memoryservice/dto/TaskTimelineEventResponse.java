package ru.andreyz.memoryservice.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record TaskTimelineEventResponse(
        Long id,
        Long taskId,
        String eventType,
        String actorType,
        String actorName,
        JsonNode oldValue,
        JsonNode newValue,
        String sourceType,
        String sourceId,
        String summary,
        Instant createdAt
) {}
