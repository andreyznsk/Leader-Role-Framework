package ru.andreyz.common.jira.dto;

public record JiraAssignableUser(
        String accountId,
        String displayName,
        String email,
        boolean active
) {
}
