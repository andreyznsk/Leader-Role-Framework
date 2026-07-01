package ru.andreyz.memoryservice.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record IntakeUpdateRequest(
        String finalRoute,
        JsonNode finalPayload,
        String reviewedBy
) {}
