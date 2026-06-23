package ru.andreyz.memoryservice.dto;

import java.time.Instant;

public record ControlPluginAuditDto(
        String pluginCode,
        String action,
        String status,
        String message,
        String requestJson,
        String responseJson,
        Instant createdAt
) {
}
