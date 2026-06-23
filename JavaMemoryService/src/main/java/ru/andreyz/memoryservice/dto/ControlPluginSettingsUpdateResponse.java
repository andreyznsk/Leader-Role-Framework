package ru.andreyz.memoryservice.dto;

import java.time.LocalDateTime;
import java.util.Map;

public record ControlPluginSettingsUpdateResponse(
        String pluginCode,
        String status,
        LocalDateTime appliedAt,
        Map<String, String> applied,
        Map<String, String> ignored,
        String message
) {
}
