package ru.andreyz.common.jira.dto;

public record JiraCreateIssueRequest(
        String projectKey,
        String issueTypeId,
        String summary,
        String description,
        String assigneeAccountId
) {
}
