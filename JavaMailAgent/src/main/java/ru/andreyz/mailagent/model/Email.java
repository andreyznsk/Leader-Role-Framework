package ru.andreyz.mailagent.model;

import java.time.LocalDateTime;
import java.util.List;

public record Email(
    String id,
    String subject,
    String from,
    List<String> recipients,
    String body,
    String messageId,
    String conversationId,
    String inReplyTo,
    LocalDateTime receivedAt,
    String folder
) {
    public Email(String id,
                 String subject,
                 String from,
                 String body,
                 LocalDateTime receivedAt,
                 String folder) {
        this(id, subject, from, List.of(), body, null, null, null, receivedAt, folder);
    }
}
