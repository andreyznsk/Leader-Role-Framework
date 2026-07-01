package ru.andreyz.memoryservice.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record IntakeCreateRequest(
        String sourceType,
        String sourceId,
        JsonNode sourcePayload,
        String agentProvider,
        String agentPrompt,
        JsonNode agentResult,
        String suggestedRoute,
        JsonNode suggestedPayload,
        Double confidence,
        String createdBy
) {}
