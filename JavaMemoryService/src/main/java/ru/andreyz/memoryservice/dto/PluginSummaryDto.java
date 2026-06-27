package ru.andreyz.memoryservice.dto;

import java.time.Instant;

public record PluginSummaryDto(
        String code,
        String name,
        String type,
        boolean enabled,
        String status,
        Instant lastHeartbeatAt,
        Instant updatedAt
) {
}
