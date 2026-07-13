package ru.andreyz.common.jira.dto;

public record JiraConnectionResult(
        boolean success,
        String message,
        JiraCurrentUser currentUser
) {
}
