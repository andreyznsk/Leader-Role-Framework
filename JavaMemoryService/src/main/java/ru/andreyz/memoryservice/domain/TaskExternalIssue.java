package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("task_external_issues")
public record TaskExternalIssue(
        @Id Long id,
        Long taskId,
        String externalSystem,
        String externalId,
        String externalKey,
        String externalUrl,
        String projectKey,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String EXTERNAL_SYSTEM_JIRA = "JIRA";
}
