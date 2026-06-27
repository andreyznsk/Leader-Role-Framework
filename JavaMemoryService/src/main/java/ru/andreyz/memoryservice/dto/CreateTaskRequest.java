package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record CreateTaskRequest(
        String title,
        String description,
        String priority,
        String status,
        LocalDate dueDate,
        LocalDate date,
        String source
) {}
