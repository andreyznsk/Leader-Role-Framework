package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("captures")
public record Capture(
        @Id Long id,
        String rawText,
        String source,
        String sourceId,
        String status,
        String classified,
        String routedTo,
        String route,
        String targetType,
        String targetId,
        String targetRef,
        String filePath,
        String errorMessage,
        Instant capturedAt,
        Instant processedAt,
        Instant updatedAt,
        Instant archivedAt
) {}
