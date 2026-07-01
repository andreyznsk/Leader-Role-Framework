package ru.andreyz.memoryservice.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record IntakeApplyRequest(
        String finalRoute,
        JsonNode finalPayload,
        String reviewedBy
) {}
