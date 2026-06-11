package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("people_notes")
public record PeopleNote(
        @Id Long id,
        Long personId,
        String note,
        String tags,
        Instant createdAt
) {}
