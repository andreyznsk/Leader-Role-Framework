package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record CreatePendingTaskRequest(
        String title,
        String description,
        String emailId,
        String sender,
        String priority,
        LocalDate dueDate,
        String pendingType,
        Long suggestedTaskId,
        Double agentConfidence,
        String agentReason,
        String sourceType,
        String sourceSubject,
        String sourceSender,
        String proposedDescriptionAppend
) {}
