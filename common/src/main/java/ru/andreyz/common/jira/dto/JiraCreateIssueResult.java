package ru.andreyz.common.jira.dto;

public record JiraCreateIssueResult(
        String id,
        String key,
        String url
) {
}
