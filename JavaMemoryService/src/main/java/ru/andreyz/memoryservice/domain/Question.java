package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("questions")
public record Question(
        @Id Long id,
        String title,
        String context,
        String status,
        Instant createdAt
) {}
