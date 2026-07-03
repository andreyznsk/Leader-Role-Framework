package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("task_labels")
public record TaskLabel(
        @Id Long id,
        String name,
        String color,
        boolean archived,
        Instant createdAt,
        Instant updatedAt
) {}
