package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("person_notes")
public record PersonNameNote(
        @Id Long id,
        String personName,
        String note,
        Instant createdAt
) {}
