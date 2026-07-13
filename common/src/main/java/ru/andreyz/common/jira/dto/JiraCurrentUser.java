package ru.andreyz.common.jira.dto;

public record JiraCurrentUser(
        String accountId,
        String displayName,
        String email
) {
}
