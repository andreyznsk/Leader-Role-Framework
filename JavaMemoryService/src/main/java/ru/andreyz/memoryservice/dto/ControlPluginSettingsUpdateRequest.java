package ru.andreyz.memoryservice.dto;

import java.util.Map;

public record ControlPluginSettingsUpdateRequest(
        Map<String, String> settings
) {
}
