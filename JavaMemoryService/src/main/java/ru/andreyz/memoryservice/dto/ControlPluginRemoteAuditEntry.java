package ru.andreyz.memoryservice.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ControlPluginRemoteAuditEntry(
        LocalDateTime appliedAt,
        String status,
        List<String> changedKeys,
        String message
) {
}
