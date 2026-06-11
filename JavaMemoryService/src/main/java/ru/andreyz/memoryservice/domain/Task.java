package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;

@Table("tasks")
public record Task(
        @Id Long id,
        Long planId,
        String title,
        String description,
        String status,
        String priority,
        LocalDate dueDate,
        String source,
        String emailId,
        Integer sortOrder,
        Instant createdAt,
        Instant updatedAt
) {}
