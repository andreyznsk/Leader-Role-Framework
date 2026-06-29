package ru.andreyz.memoryservice.dto;

import java.time.Instant;

public record TaskDescriptionResponse(
        Long taskId,
        String contentMd,
        String contentHash,
        Instant updatedAt
) {}
