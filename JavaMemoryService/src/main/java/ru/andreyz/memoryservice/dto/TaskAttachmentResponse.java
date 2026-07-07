package ru.andreyz.memoryservice.dto;

import java.time.Instant;

public record TaskAttachmentResponse(
        Long id,
        Long taskId,
        String kind,
        String filename,
        String title,
        String url,
        String mimeType,
        Long fileSize,
        Instant createdAt,
        String contentUrl
) {}
