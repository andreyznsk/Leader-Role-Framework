package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("people")
public record Person(
        @Id Long id,
        String fullName,
        String login,
        String email,
        String phone,
        String domain,
        String currentTask,
        Integer capacitySprint,
        Integer capacityMonth,
        Integer capacityQuarter,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {}
