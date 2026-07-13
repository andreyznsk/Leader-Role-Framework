package ru.andreyz.memoryservice.dto;

import java.util.List;

public record JiraProjectContextDto(
        String key,
        String name,
        List<JiraIssueTypeDto> issueTypes,
        List<JiraAssignableUserDto> assignableUsers
) {
}
