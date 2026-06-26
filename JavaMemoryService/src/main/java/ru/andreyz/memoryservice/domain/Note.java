package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("notes")
public record Note(
        @Id Long id,
        String title,
        String text,
        String tags,
        String source,
        Instant createdAt
) {}
