package ru.andreyz.memoryservice.dto;

import java.time.Instant;

public record TaskLinkResponse(
        Long id,
        String direction,
        String linkType,
        Long relatedTaskId,
        String relatedTaskTitle,
        String relatedTaskStatus,
        Instant createdAt
) {}
