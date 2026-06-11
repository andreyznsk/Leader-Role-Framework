package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("incidents")
public record Incident(
        @Id Long id,
        String title,
        String severity,
        String status,
        String description,
        String rootCause,
        String actionItems,
        Instant startedAt,
        Instant resolvedAt,
        Instant createdAt
) {}
