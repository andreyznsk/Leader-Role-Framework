package ru.andreyz.mailagent.model;

import java.time.LocalDateTime;
import java.util.List;

public record ControlAuditEntry(
        LocalDateTime appliedAt,
        String status,
        List<String> changedKeys,
        String message
) {
}
