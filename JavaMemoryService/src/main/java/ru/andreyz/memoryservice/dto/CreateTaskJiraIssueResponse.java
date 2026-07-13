package ru.andreyz.memoryservice.dto;

public record CreateTaskJiraIssueResponse(
        boolean created,
        boolean alreadyLinked,
        JiraIssueLinkDto issue
) {
}
