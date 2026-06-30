package ru.andreyz.mailagent.model;

public record PendingTaskRequest(
    String title,
    String description,
    String emailId,
    String sender,
    String priority,
    String pendingType,
    Long suggestedTaskId,
    Double agentConfidence,
    String agentReason,
    String sourceType,
    String sourceSubject,
    String sourceSender,
    String proposedDescriptionAppend
) {}
