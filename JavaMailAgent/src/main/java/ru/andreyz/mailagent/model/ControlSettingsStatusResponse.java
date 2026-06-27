package ru.andreyz.mailagent.model;

import java.time.LocalDateTime;
import java.util.Map;

public record ControlSettingsStatusResponse(
        String pluginCode,
        String status,
        LocalDateTime appliedAt,
        Map<String, String> applied,
        Map<String, String> ignored,
        String message
) {
}
