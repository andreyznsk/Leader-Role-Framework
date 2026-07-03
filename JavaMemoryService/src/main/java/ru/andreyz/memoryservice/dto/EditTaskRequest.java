package ru.andreyz.memoryservice.dto;

import java.time.LocalDate;
import java.util.List;

public record EditTaskRequest(
        String title,
        String description,
        String priority,
        String status,
        LocalDate dueDate,
        Long assignedPersonId,
        List<Long> labelIds
) {}
