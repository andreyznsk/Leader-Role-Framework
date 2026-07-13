package ru.andreyz.common.jira.dto;

public record JiraProject(
        String id,
        String key,
        String name
) {
}
