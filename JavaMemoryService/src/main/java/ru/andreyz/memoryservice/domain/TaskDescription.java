package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("task_descriptions")
public record TaskDescription(
        @Id Long id,
        Long taskId,
        String contentMd,
        String contentHash,
        Instant createdAt,
        Instant updatedAt
) {}
