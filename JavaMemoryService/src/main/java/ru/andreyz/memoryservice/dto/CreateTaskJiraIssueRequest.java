package ru.andreyz.memoryservice.dto;

public record CreateTaskJiraIssueRequest(
        String projectKey,
        String issueTypeId,
        String summary,
        String description,
        String assigneeAccountId
) {
}
