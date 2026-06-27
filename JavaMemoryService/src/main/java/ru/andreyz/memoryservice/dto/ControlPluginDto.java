package ru.andreyz.memoryservice.dto;

import java.time.Instant;

public record ControlPluginDto(
        String code,
        String name,
        String baseUrl,
        boolean enabled,
        String status,
        Instant lastSyncAt
) {
}
