package ru.andreyz.memoryservice.dto;

public record JiraIssueLinkDto(
        Long taskId,
        String externalId,
        String key,
        String url,
        String projectKey,
        String status,
        String errorMessage
) {
}
