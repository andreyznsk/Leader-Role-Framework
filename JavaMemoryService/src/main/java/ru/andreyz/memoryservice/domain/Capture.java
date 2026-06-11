package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("captures")
public record Capture(
        @Id Long id,
        String rawText,
        String source,
        String status,
        String classified,
        String routedTo,
        Instant capturedAt,
        Instant processedAt
) {}
