package ru.andreyz.memoryservice.dto;

public record CreatePendingTaskRequest(
        String title,
        String description,
        String emailId,
        String sender,
        String priority
) {}
