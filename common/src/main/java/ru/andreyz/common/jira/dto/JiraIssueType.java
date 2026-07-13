package ru.andreyz.common.jira.dto;

public record JiraIssueType(
        String id,
        String name,
        boolean subtask
) {
}
