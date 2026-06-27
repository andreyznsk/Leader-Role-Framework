package ru.andreyz.mailagent.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table(schema = "mailagent", value = "processed_emails")
public record ProcessedEmail(
    @Id Long id,
    String emailId,
    String folder,
    String sender,
    String subject,
    String agentType,
    String responseType,
    EmailProcessingStatus status,
    MailProcessingRoute failedRoute,
    String routePayloadJson,
    String actionResultJson,
    String outputPath,
    String errorMessage,
    Integer attemptsCount,
    LocalDateTime firstSeenAt,
    LocalDateTime lastAttemptAt,
    LocalDateTime processedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static ProcessedEmail newProcessing(Email email, String responseType, LocalDateTime now) {
        return new ProcessedEmail(
            null,
            email.id(),
            email.folder(),
            email.from(),
            email.subject(),
            responseType,
            responseType,
            EmailProcessingStatus.PROCESSING,
            MailProcessingRoute.NONE,
            null,
            null,
            null,
            null,
            0,
            now,
            now,
            null,
            now,
            now
        );
    }

    public ProcessedEmail withCheckpoint(
        String responseType,
        MailProcessingRoute route,
        String routePayloadJson,
        String outputPath,
        String actionResultJson,
        LocalDateTime now
    ) {
        return new ProcessedEmail(
            id,
            emailId,
            folder,
            sender,
            subject,
            responseType,
            responseType,
            EmailProcessingStatus.PROCESSING,
            route,
            routePayloadJson,
            actionResultJson,
            outputPath,
            null,
            attemptsCount != null ? attemptsCount : 0,
            firstSeenAt != null ? firstSeenAt : now,
            now,
            processedAt,
            createdAt != null ? createdAt : now,
            now
        );
    }

    public ProcessedEmail withError(String errorMessage, LocalDateTime now) {
        return new ProcessedEmail(
            id,
            emailId,
            folder,
            sender,
            subject,
            agentType,
            responseType,
            EmailProcessingStatus.ERROR,
            failedRoute,
            routePayloadJson,
            actionResultJson,
            outputPath,
            errorMessage,
            (attemptsCount != null ? attemptsCount : 0) + 1,
            firstSeenAt != null ? firstSeenAt : now,
            now,
            processedAt,
            createdAt != null ? createdAt : now,
            now
        );
    }

    public ProcessedEmail withProcessed(String outputPath, String actionResultJson, LocalDateTime now) {
        return new ProcessedEmail(
            id,
            emailId,
            folder,
            sender,
            subject,
            agentType,
            responseType,
            EmailProcessingStatus.PROCESSED,
            MailProcessingRoute.NONE,
            null,
            actionResultJson,
            outputPath,
            null,
            attemptsCount != null ? attemptsCount : 0,
            firstSeenAt != null ? firstSeenAt : now,
            now,
            now,
            createdAt != null ? createdAt : now,
            now
        );
    }
}
