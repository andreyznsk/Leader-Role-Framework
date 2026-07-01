package ru.andreyz.memoryservice.dto;

import java.util.UUID;

public record AgentProposalResponse(
        UUID intakeId,
        String status,
        String suggestedRoute,
        String reviewUrl,
        String message
) {}
