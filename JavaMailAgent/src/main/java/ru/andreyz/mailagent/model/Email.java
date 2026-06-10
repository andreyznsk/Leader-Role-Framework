package ru.andreyz.mailagent.model;

import java.time.LocalDateTime;

public record Email(
    String id,
    String subject,
    String from,
    String body,
    LocalDateTime receivedAt,
    String folder
) {}
