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
        String pendingType,
        Long suggestedTaskId,
        Double agentConfidence,
        String agentReason,
        String sourceType,
        String sourceSubject,
        String sourceSender,
        String proposedDescriptionAppend,
        Long linkedToTaskId,
        Instant linkedAt,
        Integer sortOrder,
        Instant createdAt,
        Instant updatedAt
) {}
