package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("risks")
public record Risk(
        @Id Long id,
        String title,
        String description,
        String probability,
        String impact,
        String status,
        String mitigation,
        Instant createdAt,
        Instant updatedAt
) {}
