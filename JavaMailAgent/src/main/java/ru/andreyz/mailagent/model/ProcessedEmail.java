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
    String outputPath,
    LocalDateTime processedAt
) {
    public static ProcessedEmail of(Email email, String agentType, String outputPath) {
        return new ProcessedEmail(null, email.id(), email.folder(), email.from(),
            email.subject(), agentType, outputPath, LocalDateTime.now());
    }
}
