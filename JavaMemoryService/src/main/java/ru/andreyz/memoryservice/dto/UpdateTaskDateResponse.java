package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;

public record UpdateTaskDateResponse(
        Long id,
        LocalDate date,
        LocalDate dueDate
) {}
