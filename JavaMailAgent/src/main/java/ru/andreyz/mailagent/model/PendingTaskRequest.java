package ru.andreyz.mailagent.model;

public record PendingTaskRequest(
    String title,
    String description,
    String emailId,
    String sender,
    String priority
) {}
