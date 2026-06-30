package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("task_events")
public record TaskEvent(
        @Id Long id,
        Long taskId,
        String eventType,
        String actorType,
        String actorName,
        String oldValueJson,
        String newValueJson,
        String sourceType,
        String sourceId,
        String summary,
        Instant createdAt
) {}
