package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record CreateTaskRequest(
        String title,
        String description,
        String priority,
        LocalDate date,
        String source
) {}
