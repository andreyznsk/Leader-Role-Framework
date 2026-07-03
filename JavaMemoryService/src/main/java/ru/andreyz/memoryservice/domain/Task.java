package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Table("tasks")
public record Task(
        @Id Long id,
        Long planId,
        String title,
        String description,
        String status,
        String priority,
        LocalDate dueDate,
        Long assignedPersonId,
        @Transient Person assignedPerson,
        @Transient List<Long> labelIds,
        @Transient List<TaskLabel> labels,
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
) {
    @PersistenceCreator
    public Task(
            Long id,
            Long planId,
            String title,
            String description,
            String status,
            String priority,
            LocalDate dueDate,
            Long assignedPersonId,
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
    ) {
        this(
                id,
                planId,
                title,
                description,
                status,
                priority,
                dueDate,
                assignedPersonId,
                null,
                List.of(),
                List.of(),
                source,
                emailId,
                pendingType,
                suggestedTaskId,
                agentConfidence,
                agentReason,
                sourceType,
                sourceSubject,
                sourceSender,
                proposedDescriptionAppend,
                linkedToTaskId,
                linkedAt,
                sortOrder,
                createdAt,
                updatedAt
        );
    }
}
