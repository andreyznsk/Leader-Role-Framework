package ru.andreyz.memoryservice.dto;

import java.util.List;

public record TaskJiraContextResponse(
        String integrationStatus,
        boolean enabled,
        String message,
        Long taskId,
        String taskTitle,
        String summary,
        String description,
        String defaultProject,
        String defaultIssueType,
        JiraIssueLinkDto existingIssue,
        List<JiraProjectContextDto> projects,
        JiraAssignableUserDto currentUser,
        JiraAssignableUserDto matchedAssignee
) {
}
