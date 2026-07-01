package ru.andreyz.memoryservice.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IntakeItemDto(
        UUID id,
        String sourceType,
        String sourceId,
        JsonNode sourcePayload,
        String sourceText,
        String agentProvider,
        String agentPrompt,
        JsonNode agentResult,
        String agentResultText,
        String suggestedRoute,
        JsonNode suggestedPayload,
        String finalRoute,
        JsonNode finalPayload,
        String status,
        BigDecimal confidence,
        String createdBy,
        String reviewedBy,
        Instant createdAt,
        Instant reviewedAt,
        Instant appliedAt,
        Instant rejectedAt,
        String rejectReason
) {}
