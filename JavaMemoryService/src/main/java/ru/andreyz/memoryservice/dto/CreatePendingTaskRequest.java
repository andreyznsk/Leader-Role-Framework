package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record CreatePendingTaskRequest(
        String title,
        String description,
        String emailId,
        String sender,
        String priority,
        LocalDate dueDate
) {}
