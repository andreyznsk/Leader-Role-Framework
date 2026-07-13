package ru.andreyz.memoryservice.service;

import ru.andreyz.common.jira.dto.JiraCurrentUser;

public record JiraIntegrationSnapshot(
        JiraIntegrationStatus status,
        String message,
        JiraCurrentUser currentUser
) {
    public static JiraIntegrationSnapshot disabled(String message) {
        return new JiraIntegrationSnapshot(JiraIntegrationStatus.DISABLED, message, null);
    }

    public static JiraIntegrationSnapshot unavailable(String message) {
        return new JiraIntegrationSnapshot(JiraIntegrationStatus.UNAVAILABLE, message, null);
    }

    public static JiraIntegrationSnapshot available(String message, JiraCurrentUser currentUser) {
        return new JiraIntegrationSnapshot(JiraIntegrationStatus.AVAILABLE, message, currentUser);
    }
}
