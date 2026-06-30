package ru.andreyz.memoryservice.dto;

public record LinkPendingTaskRequest(
        Long targetTaskId,
        Boolean appendSummary
) {}
