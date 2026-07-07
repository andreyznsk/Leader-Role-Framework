package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("task_attachments")
public record TaskAttachment(
        @Id Long id,
        Long taskId,
        String kind,
        String filename,
        String title,
        String url,
        String mimeType,
        Long fileSize,
        String storageRef,
        Instant createdAt
) {
    public static final String KIND_FILE = "FILE";
    public static final String KIND_LINK = "LINK";
}
