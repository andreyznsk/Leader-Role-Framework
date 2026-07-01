package ru.andreyz.memoryservice.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("intake_items")
public record IntakeItem(
        @Id UUID id,
        String sourceType,
        String sourceId,
        String sourcePayloadJson,
        String sourceText,
        String agentProvider,
        String agentPrompt,
        String agentResultJson,
        String agentResultText,
        String suggestedRoute,
        String suggestedPayloadJson,
        String finalRoute,
        String finalPayloadJson,
        String status,
        BigDecimal confidence,
        String createdBy,
        String reviewedBy,
        Instant createdAt,
        Instant reviewedAt,
        Instant appliedAt,
        Instant rejectedAt,
        String rejectReason
) {}
