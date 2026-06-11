package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record EditTaskRequest(
        String title,
        String description,
        String priority,
        String status,
        LocalDate dueDate
) {}
